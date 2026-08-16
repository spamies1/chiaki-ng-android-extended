// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.AlertDialog
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.session.*
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog : DialogContents()
private object CreateErrorDialog : DialogContents()
private object PinRequestDialog : DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)

		// Setup video output based on debanding preference.
		setupVideoOutput()

		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()

				if(amplitude == 0)
					return@Observer

				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	private var debandRenderer: DebandRenderer? = null

	private fun setupVideoOutput()
	{
		val prefs = Preferences(this)
		viewModel.session.detachSurface()

		if(prefs.debandingEnabled)
		{
			// Use GLSurfaceView with debanding shader.
			binding.surfaceView.visibility = View.GONE
			binding.debandSurfaceView.visibility = View.VISIBLE

			debandRenderer = DebandRenderer { surface ->
				viewModel.session.attachToSurface(surface)
			}

			binding.debandSurfaceView.setEGLContextClientVersion(3)
			binding.debandSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
			binding.debandSurfaceView.holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
			binding.debandSurfaceView.setRenderer(debandRenderer)
			debandRenderer!!.sharpness = prefs.sharpnessIntensity
			binding.debandSurfaceView.renderMode =
				android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
		}
		else
		{
			// Use standard SurfaceView (no shader processing).
			binding.surfaceView.visibility = View.VISIBLE
			binding.debandSurfaceView.visibility = View.GONE
			viewModel.session.attachToSurfaceView(binding.surfaceView)
		}
	}

	override fun onResume()
	{
		super.onResume()

		hideSystemUI()

		if(Preferences(this).debandingEnabled)
			binding.debandSurfaceView.onResume()

		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()

		if(Preferences(this).debandingEnabled)
			binding.debandSurfaceView.onPause()

		viewModel.session.pause()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		debandRenderer?.release()
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)

		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility =
			View.SYSTEM_UI_FLAG_IMMERSIVE or
			View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
			View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
			View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
			View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
			View.SYSTEM_UI_FLAG_FULLSCREEN
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value

			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility =
			if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()

						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(
								getString(
									R.string.alert_message_session_quit,
									state.reason.toString()
								) + (if(reasonStr != null) "\n$reasonStr" else "")
							)
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()

						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
					{
						finish()
					}
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							getString(
								R.string.alert_message_session_create_error,
								state.error.errorCode.toString()
							)
						)
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()

					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request
						)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()

					dialogContents = PinRequestDialog
					dialog.show()
				}
			}

			StreamStateIdle,
			StreamStateConnecting,
			StreamStateConnected -> { }
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile

		binding.aspectRatioLayout.aspectRatio =
			videoProfile.width.toFloat() / videoProfile.height.toFloat()

		// No on-screen display-mode controls.
		// Use FIT as the fixed/default streaming mode.
		binding.aspectRatioLayout.mode = TransformMode.FIT
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
{
    val source = event.source

    // Only forward physical/game-controller button input
    // to the PS4 Remote Play session.
    if ((source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
    {
        return viewModel.input.dispatchKeyEvent(event)
    }

    // Ignore Android keyboard/input events.
    return super.dispatchKeyEvent(event)
}

override fun onGenericMotionEvent(event: MotionEvent): Boolean
{
    val source = event.source

    // Only forward analog/controller motion to the PS4.
    if ((source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
    {
        return viewModel.input.onGenericMotionEvent(event)
    }

    // Ignore non-controller motion input.
    return super.onGenericMotionEvent(event)
}
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM
}
