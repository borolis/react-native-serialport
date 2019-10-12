package com.melihyarikkaya.rnserialport;

import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.felhr.usbserial.SerialInputStream;
import com.felhr.usbserial.SerialOutputStream;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.melihyarikkaya.rnserialport.Definitions.bytesToHex;
import static com.melihyarikkaya.rnserialport.ReactNativeEvents.onErrorEvent;
import static com.melihyarikkaya.rnserialport.ReactNativeEvents.onReadDataFromPort;
import static com.melihyarikkaya.rnserialport.SerialPortDefaultSettings.BAUD_RATE;
import static com.melihyarikkaya.rnserialport.SerialPortDefaultSettings.DATA_BIT;
import static com.melihyarikkaya.rnserialport.SerialPortDefaultSettings.FLOW_CONTROL;
import static com.melihyarikkaya.rnserialport.SerialPortDefaultSettings.PARITY;
import static com.melihyarikkaya.rnserialport.SerialPortDefaultSettings.STOP_BIT;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_CONNECT;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_NOT_OPENED;
import static com.melihyarikkaya.rnserialport.SerialPortEvents.ACTION_USB_READY;


public class SerialConnection {

    private boolean isConnectionOpened = false;

    private final ReactContext reactContext;
    private final String deviceName;
    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbSerialDevice serialPort;

    private ReadThread readThread;
    private BufferThread bufferThread;

    public String getDeviceName() {
        return deviceName;
    }

    public UsbDevice getDevice() {
        return device;
    }

    public UsbDeviceConnection getConnection() {
        return connection;
    }

    public UsbSerialDevice getSerialPort() {
        return serialPort;
    }

    public void closeConnection()
    {
        readThread.setKeep(Boolean.FALSE);
        bufferThread.setKeep(Boolean.FALSE);
        serialBuffer.clean();

        try
        {
            serialPort.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        try
        {
            connection.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        isConnectionOpened = false;
    }

    public boolean isOpened()
    {
        return isConnectionOpened;
    }

    private SerialBuffer serialBuffer;
    private SerialInputStream inputStream;
    private SerialOutputStream outputStream;

    private AtomicLong currentTime = new AtomicLong(System.currentTimeMillis());
    private AtomicLong lastDataReceivedTime = new AtomicLong(0);

    public SerialConnection(final ReactContext reactContext,
                            final String deviceName,
                            final UsbDevice device,
                            final UsbDeviceConnection connection,
                            final UsbSerialDevice serialPort) {

        this.reactContext = reactContext;
        this.deviceName = deviceName;
        this.device = device;
        this.connection = connection;
        this.serialPort = serialPort;

        if (!serialPort.syncOpen())
        {
            Intent intent2 = new Intent(ACTION_USB_NOT_OPENED);
            reactContext.sendBroadcast(intent2);
            return;
        }

        isConnectionOpened = true;
        serialPort.setBaudRate(BAUD_RATE);
        serialPort.setDataBits(DATA_BIT);
        serialPort.setStopBits(STOP_BIT);
        serialPort.setParity(PARITY);
        serialPort.setFlowControl(FLOW_CONTROL);

        inputStream = serialPort.getInputStream();
        outputStream = serialPort.getOutputStream();
        this.serialBuffer = new SerialBuffer();

        Intent intent = new Intent(ACTION_USB_READY);
        reactContext.sendBroadcast(intent);
        intent = new Intent(ACTION_USB_CONNECT);
        reactContext.sendBroadcast(intent);

        readThread = new ReadThread();
        bufferThread = new BufferThread();

        Log.d("BOROLIS", "SerialConnection: " + deviceName + " Started");
        readThread.start();
        Log.d("BOROLIS", "SerialConnection: " + deviceName + " ReadThread started");
        bufferThread.start();
        Log.d("BOROLIS", "SerialConnection: " + deviceName + " BufferThread started");
    }

    public void writeBytes(byte[] bytes)
    {
        if(!isOpened())
        {
            eventEmit(onErrorEvent, createError(Definitions.ERROR_SERIALPORT_ALREADY_DISCONNECTED,
                    Definitions.ERROR_SERIALPORT_ALREADY_DISCONNECTED_MESSAGE));
            return;
        }
        outputStream.write(bytes);
//        outputStream.write(bytes);
//        try {
//            outputStream.flush();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
    }

    private class ReadThread extends Thread {
        private AtomicBoolean keep = new AtomicBoolean(true);
        @Override
        public void run()
        {
            while(keep.get())
            {
                if(inputStream == null) {
                    return;
                }

                byte value = -1;

                try
                {
                     value = (byte) inputStream.read();
                }
                catch (Exception e)
                {
                    return;
                }
                if(value != -1)
                {
                    serialBuffer.add(value);
                    lastDataReceivedTime.set(System.currentTimeMillis());
                    Log.d("BOROLIS", "read" + ":" + value);
                }
            }
        }

        public void setKeep(boolean keep){
            this.keep.set(keep);
        }
    }

    private class BufferThread extends Thread {
        private AtomicBoolean keep = new AtomicBoolean(true);
        @Override
        public void run()
        {
            while(keep.get())
            {
                try {
                    Thread.sleep(50);
                } catch (Exception e) {
                    return;
                }
                currentTime.set(System.currentTimeMillis());
                if ((currentTime.get() - lastDataReceivedTime.get() > 200L) && (lastDataReceivedTime.get() != 0)
                        && !(serialBuffer.isEmpty()))
                {
                    String dataKey = "data";
                    String deviceNameKey = "deviceName";

                    WritableMap reactMap = Arguments.createMap();

                    reactMap.putString(deviceNameKey, deviceName);
                    reactMap.putString(dataKey, bytesToHex(serialBuffer.getBufferArray()));

                    Log.d("BOROLIS",
                            "DATA FROM SERIAL:{" + bytesToHex(serialBuffer.getBufferArray()) + "}" + '\n' +
                                    "DATA FROM DEVICE NAME:{" + deviceName + "}" + '\n');

                    eventEmit(onReadDataFromPort, reactMap);

                    serialBuffer.clean();
                }
            }
        }

        public void setKeep(boolean keep){
            this.keep.set(keep);
        }
    }

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

}
