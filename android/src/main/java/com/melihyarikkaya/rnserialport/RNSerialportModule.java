package com.melihyarikkaya.rnserialport;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.felhr.usbserial.UsbSerialDevice;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import static com.melihyarikkaya.rnserialport.ReactNativeEvents.onConnectedEvent;
import static com.melihyarikkaya.rnserialport.ReactNativeEvents.onConnectionListUpdatedEvent;
import static com.melihyarikkaya.rnserialport.ReactNativeEvents.onDisconnectedEvent;
import static com.melihyarikkaya.rnserialport.ReactNativeEvents.onErrorEvent;
import static com.melihyarikkaya.rnserialport.ReactNativeEvents.onUsbPermissionGranted;
import static com.melihyarikkaya.rnserialport.SerialPortDefaultSettings.PORT_INTERFACE;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_NO_USB;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_ATTACHED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_CONNECT;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_CONNECTION_LIST_UPDATED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_DETACHED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_DISCONNECTED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_NOT_OPENED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_NOT_SUPPORTED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_PERMISSION;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_PERMISSION_GRANTED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_PERMISSION_NOT_GRANTED;

public class RNSerialportModule extends ReactContextBaseJavaModule
{
    private final Map<String, SerialConnection> serialConnectionMap;

    private final ReactApplicationContext reactContext;
    private UsbManager usbManager;

    private Queue<UsbDevice> pendingPermissions;
    private AtomicBoolean isPermissionPending = new AtomicBoolean(false);
    private AtomicReference<String> pendingPerimissionDeviceName = new AtomicReference<>("");

    private boolean usbServiceStarted = false;

    @Override
    public String getName()
    {
        return "RNSerialport";
    }

    public RNSerialportModule(ReactApplicationContext reactContext)
    {
        super(reactContext);
        this.reactContext = reactContext;

        setFilters();
        usbManager = (UsbManager)reactContext.getSystemService(Context.USB_SERVICE);

        serialConnectionMap = new ConcurrentHashMap<>();
        pendingPermissions = new ConcurrentLinkedQueue<>();
        Thread permissionThread = new PermissionThread();
        permissionThread.start();
    }

    public class PermissionThread extends Thread
    {
        @Override
        public void run()
        {
            while(Boolean.TRUE)
            {
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                if(!pendingPermissions.isEmpty() && !isPermissionPending.get())
                {
                    UsbDevice device = pendingPermissions.poll();
                    if(device != null)
                    {
                        isPermissionPending.set(Boolean.TRUE);
                        pendingPerimissionDeviceName.set(device.getDeviceName());
                        Log.d("BOROLIS", "Sending permission intent for " + device.getDeviceName());
                        requestUserPermission(device);
                    }
                }
            }
        }
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context arg0, Intent arg1)
        {
            switch (arg1.getAction())
            {
            case ACTION_USB_CONNECTION_LIST_UPDATED:
                eventEmit(onConnectionListUpdatedEvent, getActiveConnectionMap());
                break;

            case ACTION_USB_CONNECT:
                eventEmit(onConnectedEvent, null);
                break;
            case ACTION_USB_DISCONNECTED:
                eventEmit(onDisconnectedEvent, null);
                break;
            case ACTION_USB_NOT_SUPPORTED:
                eventEmit(onErrorEvent, createError(Definitions.ERROR_DEVICE_NOT_SUPPORTED,
                        Definitions.ERROR_DEVICE_NOT_SUPPORTED_MESSAGE));
                break;
            case ACTION_USB_NOT_OPENED:
                eventEmit(onErrorEvent, createError(Definitions.ERROR_COULD_NOT_OPEN_SERIALPORT,
                        Definitions.ERROR_COULD_NOT_OPEN_SERIALPORT_MESSAGE));
                break;
            case ACTION_USB_ATTACHED:
                Log.d("BOROLIS", "ACTION_USB_ATTACHED");
                final UsbDevice attachedDevice = arg1.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                ///TODO если коннекта еще нет, добавить в список пермишенов
                SerialConnection optionalConnection = getConnectionByName(attachedDevice.getDeviceName());
                if(optionalConnection == null)
                {
                    pendingPermissions.add(attachedDevice);
                }
//                connectDevice(attachedDevice.getDeviceName());
                break;
            case ACTION_USB_DETACHED:
                Log.d("BOROLIS", "ACTION_USB_DETACHED");
                final UsbDevice detachedDevice = arg1.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                final SerialConnection detachedConnection = getConnectionByName(detachedDevice.getDeviceName());
                try{
                    detachedConnection.closeConnection();
                }
                catch (Exception e)
                {
                    Log.d("BOROLIS", "Connection with device " + detachedDevice.getDeviceName() + " is already closed");
                }
                removeConnectionByName(detachedDevice.getDeviceName());
//                eventEmit(onDeviceDetachedEvent, null);
//                if (serialPortConnected)
//                {
//                    stopConnection();
//                }
                break;
            case ACTION_USB_PERMISSION:
                boolean granted = arg1.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                if(granted)
                {
                    final UsbDevice permissionDevice = arg1.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    Log.d("BOROLIS", "ACTION_USB_PERMISSION: for" + permissionDevice.getDeviceName());

                    ///TODO чекнуть для какого пришел, если нужный
                    if(pendingPerimissionDeviceName.get().equals(permissionDevice.getDeviceName()))
                    {
                        ///TODO isPermissionPending.set(false)
                        ///TODO startConnection(permissionDevice);
                        startConnection(permissionDevice);
                        pendingPerimissionDeviceName.set("");
                        isPermissionPending.set(Boolean.FALSE);
                    }
                }
                else
                {
//                    permissionWaiting.set(false);
                    Intent intent = new Intent(ACTION_USB_PERMISSION_NOT_GRANTED);
                    reactContext.sendBroadcast(intent);
                }
                break;
            case ACTION_USB_PERMISSION_GRANTED:
                eventEmit(onUsbPermissionGranted, null);
                break;
            case ACTION_USB_PERMISSION_NOT_GRANTED:
                eventEmit(onErrorEvent, createError(Definitions.ERROR_USER_DID_NOT_ALLOW_TO_CONNECT,
                        Definitions.ERROR_USER_DID_NOT_ALLOW_TO_CONNECT_MESSAGE));
                break;
            }
        }
    };

    @ReactMethod
    public void isOpen(Promise promise)
    {
        promise.resolve(true);
    }

//    @ReactMethod
//    public void getDeviceList(Promise promise)
//    {
//
//        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
//
//        if (devices.isEmpty())
//        {
//            promise.resolve(Arguments.createArray());
//            return;
//        }
//
//        WritableArray deviceList = Arguments.createArray();
//        for (Map.Entry<String, UsbDevice> entry : devices.entrySet())
//        {
//            UsbDevice d = entry.getValue();
//
//            WritableMap map = Arguments.createMap();
//            map.putString("name", d.getDeviceName());
//            map.putInt("vendorId", d.getVendorId());
//            map.putInt("productId", d.getProductId());
//            map.putString("serialId", d.getSerialNumber());
//
//            deviceList.pushMap(map);
//        }
//        promise.resolve(deviceList);
//    }
//

    private WritableArray getActiveConnectionMap()
    {
        WritableArray deviceList = Arguments.createArray();

        for (Map.Entry<String, SerialConnection> entry : serialConnectionMap.entrySet())
        {
            SerialConnection serialConnection = entry.getValue();
            UsbDevice device = serialConnection.getDevice();
            WritableMap map = Arguments.createMap();
            map.putString("name", device.getDeviceName());
            map.putInt("vendorId", device.getVendorId());
            map.putInt("productId", device.getProductId());
            map.putString("serialId", device.getSerialNumber());

            deviceList.pushMap(map);
        }
        return deviceList;
    }
    @ReactMethod
    public void getActiveConnectionsList(Promise promise)
    {
        promise.resolve(getActiveConnectionMap());
    }

    @ReactMethod
    public void connectDevice(String deviceName)
    {
        Log.d("BOROLIS", "connectDevice reactMethod: !!!");
//        SerialConnection connectionByName = getConnectionByName(deviceName);
//        if(connectionByName != null)
//        {
//            eventEmit(onErrorEvent, createError(Definitions.ERROR_CONNECTION_FAILED,
//                    Definitions.ERROR_CONNECTION_FAILED_MESSAGE + " Already connected: " + deviceName));
//            return;
//        }
//        try
//        {
//            ///TODO send permission request to queue
//            //requestUserPermission(getDevice(deviceName));
//        }
//        catch (Exception err)
//        {
//            eventEmit(onErrorEvent, createError(Definitions.ERROR_CONNECTION_FAILED,
//                    Definitions.ERROR_CONNECTION_FAILED_MESSAGE + " Catch Error Message:" + err.getMessage()));
//        }
    }

    @ReactMethod
    public void disconnect()
    {
        Log.d("BOROLIS", "disconnect reactMethod: !!!");
//        if (!usbServiceStarted)
//        {
//            eventEmit(onErrorEvent, createError(Definitions.ERROR_USB_SERVICE_NOT_STARTED,
//                    Definitions.ERROR_USB_SERVICE_NOT_STARTED_MESSAGE));
//            return;
//        }
//
//        if (!serialPortConnected)
//        {
//            eventEmit(onErrorEvent, createError(Definitions.ERROR_SERIALPORT_ALREADY_DISCONNECTED,
//                    Definitions.ERROR_SERIALPORT_ALREADY_DISCONNECTED_MESSAGE));
//            return;
//        }
//        stopConnection();
    }

    @ReactMethod
    public void startUsbService()
    {
//        if (usbServiceStarted)
//        {
//            return;
//        }
//        usbServiceStarted = true;

//        //Return usb status when service is started.
//        WritableMap map = Arguments.createMap();
//
//        map.putBoolean("deviceAttached", !usbManager.getDeviceList().isEmpty());
//
//        eventEmit(onServiceStarted, map);

    }

    @ReactMethod
    public void stopUsbService()
    {
//        if (serialPortConnected)
//        {
//            eventEmit(onErrorEvent,
//                    createError(Definitions.ERROR_SERVICE_STOP_FAILED, Definitions.ERROR_SERVICE_STOP_FAILED_MESSAGE));
//            return;
//        }
//        if (!usbServiceStarted)
//        {
//            return;
//        }
//        reactContext.unregisterReceiver(mUsbReceiver);
//        usbServiceStarted = false;
//        eventEmit(onServiceStopped, null);
    }

@ReactMethod
  public void writeString(String deviceName, String message)
  {
    SerialConnection connectionByName = getConnectionByName(deviceName);

    if(connectionByName == null)
    {
    eventEmit(onErrorEvent, createError(Definitions.ERROR_THERE_IS_NO_CONNECTION,
            Definitions.ERROR_THERE_IS_NO_CONNECTION_MESSAGE));
    return;
    }

    if(!connectionByName.isOpened())
    {
    eventEmit(onErrorEvent, createError(Definitions.ERROR_THERE_IS_NO_CONNECTION,
            Definitions.ERROR_THERE_IS_NO_CONNECTION_MESSAGE));
    return;
    }

    if (message.length() < 1)
    {
    return;
    }
    connectionByName.writeBytes(message.getBytes());
  }


    @ReactMethod
    public void writeHexString(String deviceName, String message)
    {
        ///TODO try to get device by name
        ///TODO write bytes to device

        SerialConnection connectionByName = getConnectionByName(deviceName);

        if(connectionByName == null)
        {
            eventEmit(onErrorEvent, createError(Definitions.ERROR_THERE_IS_NO_CONNECTION,
                    Definitions.ERROR_THERE_IS_NO_CONNECTION_MESSAGE));
            return;
        }

        if(!connectionByName.isOpened())
        {
            eventEmit(onErrorEvent, createError(Definitions.ERROR_THERE_IS_NO_CONNECTION,
                    Definitions.ERROR_THERE_IS_NO_CONNECTION_MESSAGE));
            return;
        }

        if (message.length() < 1)
        {
            return;
        }

        byte[] data = new byte[message.length() / 2];
        for (int i = 0; i < data.length; i++)
        {
            int index = i * 2;

            String hex = message.substring(index, index + 2);

            if (Definitions.hexChars.indexOf(hex.substring(0, 1)) == -1
                    || Definitions.hexChars.indexOf(hex.substring(1, 1)) == -1)
            {
                return;
            }

            int v = Integer.parseInt(hex, 16);
            data[i] = (byte)v;
        }
        connectionByName.writeBytes(data);
    }

//    UsbDevice getDevice(String deviceName)
//    {
//        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
//        if (usbDevices.isEmpty())
//        {
//            return null;
//        }
//
//
//        for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet())
//        {
//            UsbDevice d = entry.getValue();
//
//            if (d.getDeviceName().equals(deviceName))
//            {
//                return d;
//            }
//        }
//
//        return null;
//    }

    private WritableMap createError(int code, String message)
    {
        WritableMap err = Arguments.createMap();
        err.putBoolean("status", false);
        err.putInt("errorCode", code);
        err.putString("errorMessage", message);

        return err;
    }

    private void eventEmit(String eventName, Object data)
    {
        try
        {
            if (reactContext.hasActiveCatalystInstance())
            {
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, data);
            }
        }
        catch (Exception error)
        {
        }
    }

    private void requestUserPermission(UsbDevice device)
    {
        if(getConnectionByName(device.getDeviceName()) != null)
        {
            Log.d("BOROLIS", "requestUserPermission: Connection with device already exists, need not to ask permission");
            return;
        }

        PendingIntent mPendingIntent = PendingIntent.getBroadcast(reactContext, 0, new Intent(ACTION_USB_PERMISSION),0);
        usbManager.requestPermission(device, mPendingIntent);
    }

    private void setFilters()
    {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_CONNECTION_LIST_UPDATED);
        filter.addAction(ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(ACTION_NO_USB);
        filter.addAction(ACTION_USB_CONNECT);
        filter.addAction(ACTION_USB_DISCONNECTED);
        filter.addAction(ACTION_USB_NOT_SUPPORTED);
        filter.addAction(ACTION_USB_PERMISSION_NOT_GRANTED);
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_ATTACHED);
        filter.addAction(ACTION_USB_DETACHED);
        reactContext.registerReceiver(mUsbReceiver, filter);
    }

    private void startConnection(UsbDevice device)
    {
        SerialConnection connectionByName = getConnectionByName(device.getDeviceName());
        if(connectionByName != null)
        {
            Log.d("BOROLIS", "Connection with device " + connectionByName.getDeviceName() + " already started");
            return;
        }
        try
        {
            UsbDeviceConnection connection = usbManager.openDevice(device);
            UsbSerialDevice serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection, PORT_INTERFACE);

            SerialConnection serialConnection = new SerialConnection(reactContext, device.getDeviceName(), device, connection, serialPort);
            serialConnectionMap.put(serialConnection.getDeviceName(), serialConnection);
            Intent intent = new Intent(ACTION_USB_CONNECTION_LIST_UPDATED);
            reactContext.sendBroadcast(intent);

        }
        catch (Exception error)
        {
            WritableMap map = createError(Definitions.ERROR_CONNECTION_FAILED,
                    Definitions.ERROR_CONNECTION_FAILED_MESSAGE);
            map.putString("exceptionErrorMessage", error.getMessage());
            eventEmit(onErrorEvent, map);
        }
    }

    public SerialConnection getConnectionByName(String deviceName)
    {
        for (Map.Entry<String, SerialConnection> connection: serialConnectionMap.entrySet()) {
            if(connection.getKey().equals(deviceName))
            {
                return connection.getValue();
            }
        }
        return null;
    }

    public void removeConnectionByName(String deviceName)
    {
        for (Map.Entry<String, SerialConnection> connection: serialConnectionMap.entrySet()) {
            if(connection.getKey().equals(deviceName))
            {
                serialConnectionMap.remove(connection.getKey());
                Intent intent = new Intent(ACTION_USB_CONNECTION_LIST_UPDATED);
                reactContext.sendBroadcast(intent);
                return;
            }
        }
    }

    private void stopConnection()
    {
//        if (serialPortConnected)
//        {
////            serialPort.close();
////            connection = null;
////            device = null;
//            Intent intent = new Intent(ACTION_USB_DISCONNECTED);
//            reactContext.sendBroadcast(intent);
////            serialPortConnected = false;
//        }
//        else
//        {
//            Intent intent = new Intent(ACTION_USB_DETACHED);
//            reactContext.sendBroadcast(intent);
//        }
    }
}
