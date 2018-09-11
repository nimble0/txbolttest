package nimble.txbolttest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.support.v7.app.AppCompatActivity
import android.widget.*
import com.felhr.usbserial.CDCSerialDevice
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface

import java.lang.ref.WeakReference

val TXBOLT_LAYOUT = KeyLayout(
	"STKPWHR",
	"AO*EU",
	"FRPBLGTSDZ#",
	mapOf()
)

val ACTION_USB_READY = "com.felhr.connectivityservices.USB_READY"
val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
val ACTION_USB_NOT_SUPPORTED = "com.felhr.usbservice.USB_NOT_SUPPORTED"
val ACTION_NO_USB = "com.felhr.usbservice.NO_USB"
val ACTION_USB_PERMISSION_GRANTED = "com.felhr.usbservice.USB_PERMISSION_GRANTED"
val ACTION_USB_PERMISSION_NOT_GRANTED = "com.felhr.usbservice.USB_PERMISSION_NOT_GRANTED"
val ACTION_USB_DISCONNECTED = "com.felhr.usbservice.USB_DISCONNECTED"
val ACTION_CDC_DRIVER_NOT_WORKING = "com.felhr.connectivityservices.ACTION_CDC_DRIVER_NOT_WORKING"
val ACTION_USB_DEVICE_NOT_WORKING = "com.felhr.connectivityservices.ACTION_USB_DEVICE_NOT_WORKING"
val MESSAGE_FROM_SERIAL_PORT = 0
private val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
private val BAUD_RATE = 9600 // BaudRate. Change this value if you need
var SERVICE_CONNECTED = false

private val DATA_BITS_MAP = mapOf(
	Pair("5", UsbSerialInterface.DATA_BITS_5),
	Pair("6", UsbSerialInterface.DATA_BITS_6),
	Pair("7", UsbSerialInterface.DATA_BITS_7),
	Pair("8", UsbSerialInterface.DATA_BITS_8)
)

private val STOP_BITS_MAP = mapOf(
	Pair("1", UsbSerialInterface.STOP_BITS_1),
	Pair("1.5", UsbSerialInterface.STOP_BITS_15),
	Pair("2", UsbSerialInterface.STOP_BITS_2)
)

private val PARITY_MAP = mapOf(
	Pair("None", UsbSerialInterface.PARITY_NONE),
	Pair("Odd", UsbSerialInterface.PARITY_ODD),
	Pair("Even", UsbSerialInterface.PARITY_EVEN),
	Pair("Mark", UsbSerialInterface.PARITY_MARK),
	Pair("Space", UsbSerialInterface.PARITY_SPACE)
)

private val FLOW_CONTROL_MAP = mapOf(
	Pair("Off", UsbSerialInterface.FLOW_CONTROL_OFF),
	Pair("RTS CTS", UsbSerialInterface.FLOW_CONTROL_RTS_CTS),
	Pair("DSR DTR", UsbSerialInterface.FLOW_CONTROL_DSR_DTR),
	Pair("XON XOFF", UsbSerialInterface.FLOW_CONTROL_XON_XOFF)
)

class MainActivity : AppCompatActivity()
{
	private var log: EditText? = null

	private val deviceList = mutableListOf<String>()
	private var deviceListAdapter: ArrayAdapter<String>? = null
	private var devices: Spinner? = null
	private var baudRate: EditText? = null
	private var dataBits: Spinner? = null
	private var stopBits: Spinner? = null
	private var parity: Spinner? = null
	private var flowControl: Spinner? = null

	private var usbManager: UsbManager? = null
	private var device: UsbDevice? = null
	private var connection: UsbDeviceConnection? = null
	private var serialPort: UsbSerialDevice? = null

	private var serialPortConnected: Boolean = false
	/*
     * Different notifications from OS will be received here (USB attached, detached, permission responses...)
     * About BroadcastReceiver: http://developer.android.com/reference/android/content/BroadcastReceiver.html
     */
	private val usbReceiver = object : BroadcastReceiver()
	{
		override fun onReceive(context: Context, intent: Intent)
		{
			val message = when(intent.action)
			{
				ACTION_USB_PERMISSION_GRANTED ->
					"USB Ready"
				ACTION_USB_PERMISSION_NOT_GRANTED ->
					"USB Permission not granted"
				ACTION_NO_USB ->
					"No USB connected"
				ACTION_USB_DISCONNECTED ->
					"USB disconnected"
				ACTION_USB_NOT_SUPPORTED ->
					"USB device not supported"
				else ->
					null
			}
			if(message != null)
			{
				Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
				log?.append("$message\n")
			}

			when(intent.action)
			{
				ACTION_USB_PERMISSION ->
				{
					val granted = intent.extras!!.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
					if(granted)
					// User accepted our USB connection. Try to open the device as a serial port
					{
						val intent = Intent(ACTION_USB_PERMISSION_GRANTED)
						context.sendBroadcast(intent)
						log?.append("Device access permission granted\n")
						connection = usbManager!!.openDevice(device)
						serialPortConnected = true
						openDevice()
					}
					else
					// User not accepted our USB connection. Send an Intent to the Main Activity
					{
						val intent = Intent(ACTION_USB_PERMISSION_NOT_GRANTED)
						context.sendBroadcast(intent)
						log?.append("Device access permission not granted\n")
					}
				}
				ACTION_USB_ATTACHED ->
				{
					refreshDevices()
//					if(!serialPortConnected)
//						findSerialPortDevice() // A USB device has been attached. Try to open it as a Serial port
				}
				ACTION_USB_DETACHED ->
				{
					refreshDevices()
//					// Usb device was disconnected. send an intent to the Main Activity
//					val intent = Intent(ACTION_USB_DISCONNECTED)
//					context.sendBroadcast(intent)
//					serialPortConnected = false
//					serialPort!!.close()
				}
			}
		}
	}

	/*
     * Request user permission. The response will be received in the BroadcastReceiver
     */
	private fun requestUserPermission(deviceName: String?)
	{
		if(deviceName == null)
		{
			this.log?.append("No device selected\n")
			return
		}

		device = usbManager!!.deviceList[deviceName]
		if(device == null)
		{
			this.log?.append("Invalid device: $deviceName\n")
			return
		}

		val mPendingIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
		usbManager!!.requestPermission(device, mPendingIntent)
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		this.usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
//		findSerialPortDevice()

		this.log = this.findViewById(R.id.log)

		val devicesSpinner = this.findViewById<Spinner>(R.id.devices)
		this.deviceListAdapter = ArrayAdapter(
			this,
			android.R.layout.simple_spinner_item,
			this.deviceList)
		devicesSpinner.adapter = deviceListAdapter

		this.devices = this.findViewById(R.id.devices)
		this.baudRate = this.findViewById(R.id.baudrate)
		this.dataBits = this.findViewById(R.id.data_bits)
		this.stopBits = this.findViewById(R.id.stop_bits)
		this.parity = this.findViewById(R.id.parity)
		this.flowControl = this.findViewById(R.id.flow_control)

		this.findViewById<Button>(R.id.open_device).setOnClickListener({
			this.requestUserPermission(this.devices?.selectedItem as? String)
		})
	}

	public override fun onResume()
	{
		super.onResume()
		refreshDevices()
		setFilters()  // Start listening notifications from UsbService
	}

	public override fun onPause()
	{
		super.onPause()
		unregisterReceiver(this.usbReceiver)
	}

	private fun setFilters()
	{
		val filter = IntentFilter()
		filter.addAction(ACTION_USB_PERMISSION)
		filter.addAction(ACTION_USB_DETACHED)
		filter.addAction(ACTION_USB_ATTACHED)

		filter.addAction(ACTION_USB_PERMISSION_GRANTED)
		filter.addAction(ACTION_NO_USB)
		filter.addAction(ACTION_USB_DISCONNECTED)
		filter.addAction(ACTION_USB_NOT_SUPPORTED)
		filter.addAction(ACTION_USB_PERMISSION_NOT_GRANTED)
		registerReceiver(this.usbReceiver, filter)
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
			this.log?.append("Stroke: ${currentStroke.rtfcre}")
			currentStroke = Stroke(TXBOLT_LAYOUT, 0L)
		}
	}

	private fun refreshDevices()
	{
		this.deviceList.clear()
		this.deviceList.addAll(this.usbManager!!.deviceList.keys)
		this.deviceListAdapter?.notifyDataSetChanged()
		this.log!!.append("Found ${this.usbManager!!.deviceList.size} devices\n")
	}

	private fun openDevice()
	{
		serialPort = UsbSerialDevice.createUsbSerialDevice(device!!, connection)
		if(serialPort != null)
		{
			if(serialPort!!.open())
			{
				serialPort!!.setBaudRate(
					this.baudRate?.text?.toString()?.toIntOrNull()
						?: 9600)
				serialPort!!.setDataBits(
					DATA_BITS_MAP[this.dataBits?.selectedItem as? String]
						?: UsbSerialInterface.DATA_BITS_8)
				serialPort!!.setStopBits(
					STOP_BITS_MAP[this.stopBits?.selectedItem as? String]
						?: UsbSerialInterface.STOP_BITS_1)
				serialPort!!.setParity(
					PARITY_MAP[this.parity?.selectedItem as? String]
						?: UsbSerialInterface.PARITY_NONE)
				serialPort!!.setFlowControl(
					FLOW_CONTROL_MAP[this.flowControl?.selectedItem as? String]
						?: UsbSerialInterface.FLOW_CONTROL_OFF)

				serialPort!!.read({ this.receiveBytes(it) })

				// Everything went as expected. Send an intent to MainActivity
				val intent = Intent(ACTION_USB_READY)
				this.sendBroadcast(intent)
				this.log!!.append("Opened device and listening\n")
			}
			else
			{
				// Serial port could not be opened, maybe an I/O error or if CDC driver was chosen, it does not really fit
				// Send an Intent to Main Activity
				if(serialPort is CDCSerialDevice)
				{
					val intent = Intent(ACTION_CDC_DRIVER_NOT_WORKING)
					this.sendBroadcast(intent)
					this.log!!.append("CDC device driver not working\n")
				}
				else
				{
					val intent = Intent(ACTION_USB_DEVICE_NOT_WORKING)
					this.sendBroadcast(intent)
					this.log!!.append("Device driver not working\n")
				}
			}
		}
		else
		{
			// No driver for given device, even generic CDC driver could not be loaded
			val intent = Intent(ACTION_USB_NOT_SUPPORTED)
			this.sendBroadcast(intent)
			this.log!!.append("No device driver\n")
		}
	}
}
