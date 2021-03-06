package com.vehicle.uart;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.ui.ActivityMainView;
import com.utility.DebugLogger;
import com.vehicle.uart.DevMaster;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class UartService extends Service
{
//	private static final UartService singltonInstance = new UartService();

	public static UartService instanceBk;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    boolean isSending = false;
    BluetoothDevice mDevice;
    DevMaster evMaster;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.vehicle.uart.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.vehicle.uart.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.vehicle.uart.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.vehicle.uart.ACTION_DATA_AVAILABLE";
    public final static String ACTION_DATA_SENT =
            "com.vehicle.uart.ACTION_DATA_SENT";
    public final static String EXTRA_DATA =
            "com.vehicle.uart.EXTRA_DATA";
    public final static String DEVICE_DOES_NOT_SUPPORT_UART =
            "com.vehicle.uart.DEVICE_DOES_NOT_SUPPORT_UART";
    public final static String ACTION_SEND_TIMEOUT =
            "com.vehicle.uart.SENDTIMEOUT";

    public static final UUID TX_POWER_UUID = UUID.fromString("00001804-0000-1000-8000-00805f9b34fb");
    public static final UUID TX_POWER_LEVEL_UUID = UUID.fromString("00002a07-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID FIRMWARE_REVISON_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    public static final UUID DIS_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static final UUID RX_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private BluetoothGattCharacteristic mTXCharacteristic, mRXCharacteristic;

    public UartService()
    {
    	instanceBk = this;
    }

    public static UartService getInstance()
    {
    	return instanceBk;
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback()
    {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            String intentAction;

            if (newState == BluetoothProfile.STATE_CONNECTED)
			{
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
				DebugLogger.d("Connected to GATT server.");
                // Attempts to discover services after successful connection.
				DebugLogger.d("Attempting to start service discovery:" + mBluetoothGatt.discoverServices());

            }
			else if (newState == BluetoothProfile.STATE_DISCONNECTED)
			{
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
				DebugLogger.d("Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            if (status == BluetoothGatt.GATT_SUCCESS)
			{
            	DebugLogger.d("mBluetoothGatt = " + mBluetoothGatt);
            	if (isRequiredServiceSupported(mBluetoothGatt))
            	    broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            }
			else
			{
				DebugLogger.d("onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status)
		{
            if (status == BluetoothGatt.GATT_SUCCESS)
			{
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

    	@Override
    	public void onCharacteristicWrite (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
    	{
    		if (BluetoothGatt.GATT_SUCCESS == status)
    		{
    		    isSending = false;
    			broadcastUpdate(ACTION_DATA_SENT, characteristic);
    		}
    	}
//    	public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
//    		final String data = characteristic.getStringValue(0);
//    		broadcast
//    		mCallbacks.onDataSent(data);
//    	}

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic)
		{
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    public boolean isRequiredServiceSupported(final BluetoothGatt gatt) {
        final BluetoothGattService service = gatt.getService(RX_SERVICE_UUID);
        if (service != null) {
            mTXCharacteristic = service.getCharacteristic(TX_CHAR_UUID);
            mRXCharacteristic = service.getCharacteristic(RX_CHAR_UUID);
        }
        return mTXCharacteristic != null && mRXCharacteristic != null;
    }

    private void broadcastUpdate(final String action)
	{
        final Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic)
	{
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (TX_CHAR_UUID.equals(characteristic.getUuid()))
		{
            intent.putExtra(EXTRA_DATA, characteristic.getValue());
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public class LocalBinder extends Binder
	{
        public UartService getService()
		{
            return UartService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        evMaster = ActivityMainView.evDevice;
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize()
    {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null)
		{
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null)
			{
				DebugLogger.d("Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null)
		{
			DebugLogger.d("Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address)
    {
        if (mBluetoothAdapter == null || address == null)
		{
			DebugLogger.d("BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null)
        {
			DebugLogger.d("Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect())
			{
                mConnectionState = STATE_CONNECTING;
                return true;
            }
			else
			{
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null)
		{
			DebugLogger.d("Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, true, mGattCallback);
		DebugLogger.d("Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;

        mDevice = device;
        return true;
    }

    Runnable sendTimeoutRunable = new Runnable() {
        @Override
        public void run()
        {
            isSending = false;
            broadcastUpdate(ACTION_SEND_TIMEOUT);
        }
    };

    public void send()
    {
        if (evMaster == null)
        {
            DebugLogger.e("failed send: evMaster is null");
            return;
        }

        byte[] pkg = evMaster.getPackage();
        if (null == pkg)
            return;

        if (isSending) {
            DebugLogger.w("uart service is sending");
            return;
        }

        Handler timeout = new Handler();
        timeout.postDelayed(sendTimeoutRunable, 800);
        isSending = true;
        writeRXCharacteristic(pkg);
//        DebugLogger.w("send data:" + Arrays.toString(pkg));
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect()
    {
        if (mBluetoothAdapter == null || mBluetoothGatt == null)
		{
			DebugLogger.d("BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
        mDevice = null;
       // mBluetoothGatt.close();
    }

    public BluetoothDevice getDevice()
    {
    	return mDevice;
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close()
    {
        if (mBluetoothGatt == null)
		{
            return;
        }
		DebugLogger.d("mBluetoothGatt closed");
        mBluetoothDeviceAddress = null;
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic)
    {
        if (mBluetoothAdapter == null || mBluetoothGatt == null)
		{
			DebugLogger.d("BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    /*
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            DebugLogger.d("BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);


        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }*/

    /**
     * Enable TXNotification
     *
     * @return
     */
    @SuppressLint("InlinedApi")
    public void enableTXNotification()
    {
    	/*
    	if (mBluetoothGatt == null) {
    		showMessage("mBluetoothGatt null" + mBluetoothGatt);
    		broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
    		return;
    	}
    		*/
        /*
    	BluetoothGattService RxService = mBluetoothGatt.getService(RX_SERVICE_UUID);
    	if (RxService == null)
		{
            showMessage("Rx service not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
            return;
        }
    	BluetoothGattCharacteristic TxChar = RxService.getCharacteristic(TX_CHAR_UUID);
        if (TxChar == null)
		{
            showMessage("Tx charateristic not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(TxChar,true);*/
        mBluetoothGatt.setCharacteristicNotification(mTXCharacteristic,true);
        BluetoothGattDescriptor descriptor = mTXCharacteristic.getDescriptor(CCCD);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);
    }

    public void writeRXCharacteristic(byte[] value)
    {
        if (mBluetoothGatt == null)
        {
            DebugLogger.e("writeRxCharacteristic failed, mBluetoothGatt is null");
            return;
        }
        
        if (mRXCharacteristic == null)
        {
            DebugLogger.e("mRxCharacteristic is null!");
            return;
        }
        /*
    	BluetoothGattService RxService = mBluetoothGatt.getService(RX_SERVICE_UUID);
    	showMessage("mBluetoothGatt :"+ mBluetoothGatt);
    	if (RxService == null)
		{
            showMessage("Rx service not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
            return;
        }
    	BluetoothGattCharacteristic RxChar = RxService.getCharacteristic(RX_CHAR_UUID);
        if (RxChar == null)
		{
            showMessage("Rx charateristic not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
            return;
        }
        RxChar.setValue(value);
        */
        mRXCharacteristic.setValue(value);
    	boolean status = mBluetoothGatt.writeCharacteristic(mRXCharacteristic);

		DebugLogger.d("write TXchar - status=" + status);
    }

    private void showMessage(String msg)
	{
		DebugLogger.d(msg);
    }
    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices()
    {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
