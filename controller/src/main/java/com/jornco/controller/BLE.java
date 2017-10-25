package com.jornco.controller;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.text.TextUtils;

import com.jornco.controller.code.IronbotWriterCallback;
import com.jornco.controller.error.BLEWriterError;
import com.jornco.controller.scan.OnBLEDeviceStateChangeListener;

import java.util.List;

/**
 * 代表一个蓝牙设备
 * Created by kkopite on 2017/10/25.
 */

public class BLE extends BluetoothGattCallback {

    private final String address;

    private final String name;

    private BluetoothDevice device;

    private BLEState mState;

    private final IronbotRule mRule;

    private BluetoothGatt mGatt;

    private BluetoothGattCharacteristic mReadBGC;
    private BluetoothGattCharacteristic mWriterBGC;

    private OnBLEDeviceStateChangeListener mDeviceStateChangeListener;

    private IWriterStrategy mConnectedStrategy;
    private IWriterStrategy mDisconnectedStrategy;
    private IWriterStrategy mCurrentStrategy;

    public BLE(String address, String name, BluetoothDevice device, IronbotRule rule) {
        this.address = address;
        this.name = name;
        this.device = device;
        mRule = rule;
        mConnectedStrategy = new ConnectedWriterStrategy(address);
        mDisconnectedStrategy = new DisconnectedWriterStrategy(address);
        mCurrentStrategy = mDisconnectedStrategy;
    }

    public BLEState getState() {
        return mState;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public void connect(Context context, OnBLEDeviceStateChangeListener bleDeviceStateChangeListener) {
        this.mDeviceStateChangeListener = bleDeviceStateChangeListener;
        if (device == null) {
            changeState(BLEState.DISCONNECT);
            return;
        }
        changeState(BLEState.CONNECTING);
        mGatt = device.connectGatt(context, false, this);
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            BLELog.log(address + "连接成功");
            gatt.discoverServices();
            changeState(BLEState.CONNECTED);
            return;
        } else {
            BLELog.log(address + "连接断开");
            changeState(BLEState.DISCONNECT);
            mGatt.close();
            destroy();
        }
        switchWriterToDisconnect();
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mCurrentStrategy.writeSuccess();
        } else {
            mConnectedStrategy.writeFailure();
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        byte[] bytes = mReadBGC.getValue();
        String msg = new String(bytes);
        BLELog.log("收到设备传来的: " + msg);
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            List<BluetoothGattService> bgss = mGatt.getServices();
            for (BluetoothGattService bgs : bgss) {
                BLELog.log("find BluetoothGattService : " + bgs.getUuid().toString());
                List<BluetoothGattCharacteristic> bgcs = bgs.getCharacteristics();
                for (BluetoothGattCharacteristic bgc : bgcs) {
                    String uuid = bgc.getUuid().toString();
                    BLELog.log("find BluetoothGattCharacteristic : " + uuid);
                    if (mRule.isRead(uuid)) {
                        BLELog.log("getRead BluetoothGattCharacteristic : " + uuid);
                        mReadBGC = bgc;
                        mGatt.setCharacteristicNotification(mReadBGC, true);
                    }
                    if (mRule.isWrite(uuid)) {
                        BLELog.log("getWrite BluetoothGattCharacteristic : " + uuid);
                        switchWriterToConnect(mGatt, bgc);
                    }
                }
            }
        }
    }

    private void switchWriterToConnect(BluetoothGatt gatt, BluetoothGattCharacteristic bgc){
        synchronized (this){
            mCurrentStrategy.stop();
            gatt.setCharacteristicNotification(bgc, true);
            mCurrentStrategy = mConnectedStrategy;
            mCurrentStrategy.start(gatt, bgc);
        }
    }

    private void switchWriterToDisconnect(){
        synchronized (this){
            mCurrentStrategy.stop();
            mCurrentStrategy = mDisconnectedStrategy;
            mCurrentStrategy.start(null, null);
        }
    }

    public void writeData(String data, IronbotWriterCallback callback) {
        mConnectedStrategy.write(data, callback);
    }

    public void send(String cmd) {
        if (cmd == null || cmd.length() > 20) {
            BLELog.log("写入数据不可未空或大于20: " + cmd);
            return;
        }
        if (mWriterBGC == null || mGatt == null) {
            BLELog.log(address + " 写入失败: " + cmd);
            return;
        }
        mWriterBGC.setValue(cmd);
        mGatt.writeCharacteristic(mWriterBGC);

    }

    public void disconnect() {
        if (mGatt == null) {
            return;
        }
        mGatt.disconnect();
    }


    private void destroy() {
        mReadBGC = null;
        mWriterBGC = null;
        mGatt = null;
        mDeviceStateChangeListener = null;
    }

    private void changeState(BLEState state) {
        mState = state;
        mDeviceStateChangeListener.bleDeviceStateChange(address, state);
    }
}

interface IWriterStrategy {
    void write(String data, IronbotWriterCallback callback);
    void writeSuccess();
    void writeFailure();
    void start(BluetoothGatt gatt, BluetoothGattCharacteristic writerBGC);
    void stop();
}

class ConnectedWriterStrategy implements IWriterStrategy{

    public static final int MOST_WRITE_LENGTH = 20;

    private BluetoothGattCharacteristic mWriterBGC;
    private BluetoothGatt mGatt;
    private IronbotWriterCallback mCallback;
    private String address;

    public ConnectedWriterStrategy(String address) {
        this.address = address;
    }

    @Override
    public void write(String data, IronbotWriterCallback callback) {
        if(TextUtils.isEmpty(data) || (data.length() > MOST_WRITE_LENGTH)){
            callback.writerFailure(new BLEWriterError(address, data, "发送数据不能大于20个字符长度或者小于0"));
            return;
        }
        synchronized (this){
            if((mGatt == null) || (mWriterBGC == null)){
                callback.writerFailure(new BLEWriterError(address, data, "当前设备可能已经断开"));
                return;
            } else if (mCallback != null) {
                callback.writerFailure(new BLEWriterError(address, data, "发送太快或者没做好同步, 上一个指令还未发送成功回调"));
            }
            mCallback = callback;
        }
        mWriterBGC.setValue(data);
        mGatt.writeCharacteristic(mWriterBGC);
    }

    @Override
    public void writeSuccess() {
        synchronized (this){
            if(mCallback != null) {
                mCallback.writerSuccess();
            }
            mCallback = null;
        }
    }

    @Override
    public void writeFailure() {
        synchronized (this){
            if(mCallback != null) {
                String data = "";
                if (mWriterBGC != null) {
                    data = new String(mWriterBGC.getValue());
                }
                mCallback.writerFailure(new BLEWriterError(address, data, "发送出现异常"));
            }
            mCallback = null;
        }
    }

    @Override
    public void start(BluetoothGatt gatt, BluetoothGattCharacteristic writerBGC) {
        mGatt = gatt;
        mWriterBGC = writerBGC;
    }

    @Override
    public void stop() {
        synchronized (this) {
            mWriterBGC = null;
            mGatt = null;
            if(mCallback != null){
                mCallback.writerFailure(new BLEWriterError(address, "", "当前设备断开"));
            }
            mCallback = null;
        }
    }
}

class DisconnectedWriterStrategy implements IWriterStrategy{

    private String address;

    public DisconnectedWriterStrategy(String address) {
        this.address = address;
    }

    @Override
    public void write(String data, IronbotWriterCallback callback) {
        callback.writerFailure(new BLEWriterError(address, data, "当前设备已断开"));
    }

    @Override
    public void writeSuccess() {

    }

    @Override
    public void writeFailure() {

    }

    @Override
    public void start(BluetoothGatt gatt, BluetoothGattCharacteristic writerBGC) {

    }

    @Override
    public void stop() {

    }
}