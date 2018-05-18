package nimble.txbolttest

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.support.v7.app.AppCompatActivity
import android.widget.EditText
import android.widget.Toast

import java.lang.ref.WeakReference

val TXBOLT_LAYOUT = KeyLayout(
	"STKPWHR",
	"AO*EU",
	"FRPBLGTSDZ#",
	mapOf()
)

class MainActivity : AppCompatActivity()
{
	/*
     * Notifications from UsbService will be received here.
     */
	private val mUsbReceiver = object : BroadcastReceiver()
	{
		override fun onReceive(context: Context, intent: Intent)
		{
			val message = when(intent.action)
			{
				UsbService.ACTION_USB_PERMISSION_GRANTED ->
					"USB Ready"
				UsbService.ACTION_USB_PERMISSION_NOT_GRANTED ->
					"USB Permission not granted"
				UsbService.ACTION_NO_USB ->
					"No USB connected"
				UsbService.ACTION_USB_DISCONNECTED ->
					"USB disconnected"
				UsbService.ACTION_USB_NOT_SUPPORTED ->
					"USB device not supported"
				else ->
					null
			}

			if(message != null)
			{
				Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
				log?.append("$message\n")
			}
		}
	}
	private var usbService: UsbService? = null
	private var log: EditText? = null
	private var strokes: EditText? = null
	private var handler: MyHandler? = null
	private val usbConnection = object : ServiceConnection
	{
		override fun onServiceConnected(name: ComponentName, binder: IBinder)
		{
			usbService = (binder as UsbService.UsbBinder).service
			usbService!!.handler = handler
		}

		override fun onServiceDisconnected(name: ComponentName)
		{
			usbService = null
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		handler = MyHandler(this)

		this.log = this.findViewById(R.id.log)
		this.strokes = this.findViewById(R.id.strokes)
	}

	public override fun onResume()
	{
		super.onResume()
		setFilters()  // Start listening notifications from UsbService
		startService(UsbService::class.java, usbConnection, null) // Start UsbService(if it was not started before) and Bind it
	}

	public override fun onPause()
	{
		super.onPause()
		unregisterReceiver(mUsbReceiver)
		unbindService(usbConnection)
	}

	private fun startService(service: Class<*>, serviceConnection: ServiceConnection, extras: Bundle?)
	{
		if(!UsbService.SERVICE_CONNECTED)
		{
			val startService = Intent(this, service)
			if(extras != null && !extras.isEmpty)
			{
				val keys = extras.keySet()
				for(key in keys)
				{
					val extra = extras.getString(key)
					startService.putExtra(key, extra)
				}
			}
			startService(startService)
		}
		val bindingIntent = Intent(this, service)
		bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE)
	}

	private fun setFilters()
	{
		val filter = IntentFilter()
		filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED)
		filter.addAction(UsbService.ACTION_NO_USB)
		filter.addAction(UsbService.ACTION_USB_DISCONNECTED)
		filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED)
		filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED)
		registerReceiver(mUsbReceiver, filter)
	}

	var currentStroke = Stroke(TXBOLT_LAYOUT, 0L)

	fun receiveBytes(data: ByteArray)
	{
		val hexData = data.joinToString(
			separator = " ",
			transform = { String.format("%02X", it)})
		this.log!!.append("Received: $hexData\n")

		for(b in data)
		{
			val keySet = b.toInt() shr 6
			val keys = (b.toLong() and 0b111111) shl keySet*6
			this.currentStroke += Stroke(TXBOLT_LAYOUT, keys)

			if(keySet == 3 || b.toInt() == 0)
				this.finishStroke()
		}
	}

	fun finishStroke()
	{
		if(currentStroke.keys != 0L)
		{
			this.strokes?.append(currentStroke.rtfcre)
			currentStroke = Stroke(TXBOLT_LAYOUT, 0L)
		}
	}

	/*
     * This handler will be passed to UsbService. Data received from serial port is displayed through this handler
     */
	private class MyHandler(activity: MainActivity) : Handler()
	{
		private val activity = WeakReference(activity)

		override fun handleMessage(msg: Message)
		{
			when(msg.what)
			{
				UsbService.MESSAGE_FROM_SERIAL_PORT ->
				{
					val data = msg.obj as ByteArray
					this.activity.get()!!.receiveBytes(data)
				}
			}
		}
	}
}
