package com.melihyarikkaya.rnserialport;

import com.felhr.usbserial.UsbSerialInterface;

public class SerialPortDefaultSettings {
    public static final int DATA_BIT = UsbSerialInterface.DATA_BITS_8;
    public static final int STOP_BIT = UsbSerialInterface.STOP_BITS_1;
    public static final int PARITY = UsbSerialInterface.PARITY_NONE;
    public static final int FLOW_CONTROL = UsbSerialInterface.FLOW_CONTROL_OFF;
    public static final int BAUD_RATE = 9600;
    public static final int PORT_INTERFACE = -1;
}
