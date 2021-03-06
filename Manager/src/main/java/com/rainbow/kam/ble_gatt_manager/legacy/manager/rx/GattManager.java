package com.rainbow.kam.ble_gatt_manager.legacy.manager.rx;

import android.app.Application;
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

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.primitives.Bytes;
import com.rainbow.kam.ble_gatt_manager.broadcast.BondDeviceBroadcastReceiver;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattDisconnectException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattNotificationCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattReadCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattResourceNotDiscoveredException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattRssiException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattWriteCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.model.BleDevice;
import com.rainbow.kam.ble_gatt_manager.legacy.model.GattObserveData;
import com.rainbow.kam.ble_gatt_manager.model.BluetoothGatts;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import rx.Observable;
import rx.Subscription;
import rx.subjects.PublishSubject;

import static android.bluetooth.BluetoothProfile.GATT;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException.NONE_ADDRESS;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException.NONE_APPLICATION;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException.NONE_BLE_DEVICE;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException.NONE_BT;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException.NOT_CONNECTED;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException.STATUS_RESULT_FAIL;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattNotificationCharacteristicException.DESCRIPTION_WRITE_FAIL;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattResourceNotDiscoveredException.NONE_SERVICES;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattResourceNotDiscoveredException.NONE_UUID_CHARACTERISTIC;
import static com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattWriteCharacteristicException.NULL_OR_EMPTY_DATA;
import static com.rainbow.kam.ble_gatt_manager.legacy.model.GattObserveData.STATE_ON_NEXT;
import static com.rainbow.kam.ble_gatt_manager.legacy.model.GattObserveData.STATE_ON_START;

/**
 * Created by kam6512 on 2015-10-29.
 */
public class GattManager implements IGattManager {

    private final Application application;
    private BleDevice bleDevice;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;

    private PublishSubject<Boolean> connectionSubject;
    private PublishSubject<Integer> rssiSubject;
    private PublishSubject<List<BluetoothGattService>> serviceSubject;
    private PublishSubject<BluetoothGattCharacteristic> readSubject;
    private PublishSubject<GattObserveData> writeSubject;
    private PublishSubject<GattObserveData> notificationSubject;
    private PublishSubject<GattObserveData> indicationSubject;

    private Subscription rssiTimerSubscription;

    private BluetoothGattCharacteristic currentWriteCharacteristic;
    private BluetoothGattCharacteristic currentNotificationCharacteristic;
    private BluetoothGattCharacteristic currentIndicationCharacteristic;

    private static final GattResourceNotDiscoveredException UUID_NOT_FOUND;
    private static final GattConnectException GATT_NOT_CONNECTED;

    static {
        UUID_NOT_FOUND = new GattResourceNotDiscoveredException(NONE_UUID_CHARACTERISTIC);
        GATT_NOT_CONNECTED = new GattConnectException(NOT_CONNECTED);
    }

    @Inject public GattManager(final Application application) {
        Preconditions.checkArgument(application != null, "Application must Be NonNull");
        this.application = application;
        setBluetooth();
    }


    private void setBluetooth() {
        if (bluetoothManager == null || bluetoothAdapter == null) {
            bluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }


    @Override public Observable<Boolean> observeConnection() {
        return observeConnection(bleDevice);
    }


    @Override
    public Observable<Boolean> observeConnection(final BleDevice bleDevice) {
        return Observable.merge(connectionSubject = PublishSubject.create(), Observable.create(subscriber -> {
            if (application == null) {
                subscriber.onError(new GattConnectException(NONE_APPLICATION));
            } else {
                setBluetooth();
            }
            if (!bluetoothAdapter.isEnabled()) {
                subscriber.onError(new GattConnectException(NONE_BT));
            }

            if (bleDevice != null) {
                this.bleDevice = bleDevice;
            } else {
                subscriber.onError(new GattConnectException(NONE_BLE_DEVICE));
            }

            if (Strings.isNullOrEmpty(this.bleDevice.getAddress())) {
                subscriber.onError(new GattConnectException(NONE_ADDRESS));
            }

            if (isConnected()) {
                subscriber.onNext(true);
            } else {
                bluetoothGatt = this.bleDevice.getDevice().connectGatt(application, false, new GattManagerCallBack());
            }
        }));
    }


    @Override public boolean isConnected() {
        return bluetoothManager.getConnectionState(bleDevice.getDevice(), GATT) == BluetoothProfile.STATE_CONNECTED;
    }


    @Override public void disconnect() {
        if (bluetoothGatt != null && bluetoothAdapter != null) {
            if (bluetoothAdapter.isEnabled() && isConnected()) {
                bluetoothGatt.disconnect();
            } else {
                closeGatt();
            }
        } else {
            connectionSubject.onError(new GattDisconnectException(bleDevice.getAddress(), NONE_BT));
        }
    }


    protected void closeGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
        }
    }


    @Override public Observable<BluetoothDevice> observeBond()
            throws GattConnectException {
        if (isConnected()) {
            final BondDeviceBroadcastReceiver receiver = new BondDeviceBroadcastReceiver(application);
            return Observable.create(receiver).doOnSubscribe(() -> bleDevice.getDevice().createBond());
        } else {
            throw GATT_NOT_CONNECTED;
        }
    }


    @Override
    public Observable<Integer> observeRssi(final long rssiUpdateTimeInterval) {
        return Observable.merge(rssiSubject = PublishSubject.create(),
                Observable.create((Observable.OnSubscribe<Integer>) subscriber -> {
                    if (isConnected()) {
                        rssiTimerSubscription = Observable.interval(rssiUpdateTimeInterval, TimeUnit.SECONDS)
                                .subscribe(aLong -> bluetoothGatt.readRemoteRssi());
                    } else {
                        subscriber.onError(GATT_NOT_CONNECTED);
                        subscriber.unsubscribe();
                    }
                }).doOnUnsubscribe(() -> rssiTimerSubscription.unsubscribe()));
    }


    @Override
    public Observable<List<BluetoothGattService>> observeDiscoverService() {
        return Observable.merge(serviceSubject = PublishSubject.create(),
                Observable.create((Observable.OnSubscribe<List<BluetoothGattService>>) subscriber -> {
                    if (isConnected()) {
                        bluetoothGatt.discoverServices();
                    } else {
                        subscriber.onError(GATT_NOT_CONNECTED);
                    }
                }));
    }


    @Override public Observable<BluetoothGattCharacteristic> observeBattery() {
        return observeRead(BluetoothGatts.BATTERY_CHARACTERISTIC_UUID);
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeRead(final UUID uuidToRead) {
        return observeRead(findCharacteristic(uuidToRead));
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeRead(final BluetoothGattCharacteristic characteristicToRead) {
        return Observable.merge(readSubject = PublishSubject.create(),
                Observable.create((Observable.OnSubscribe<BluetoothGattCharacteristic>) subscriber -> {
                    if (isConnected()) {
                        if (characteristicToRead != null) {
                            bluetoothGatt.readCharacteristic(characteristicToRead);
                        } else {
                            subscriber.onError(UUID_NOT_FOUND);
                        }
                    } else {
                        subscriber.onError(GATT_NOT_CONNECTED);
                    }
                }));
    }


    @Override public Observable<GattObserveData> observeWrite(
            final UUID uuidToWrite, final List<Byte> valuesToWrite) {
        return observeWrite(findCharacteristic(uuidToWrite), Bytes.toArray(valuesToWrite));
    }


    @Override public Observable<GattObserveData> observeWrite(
            final UUID uuidToWrite, final byte[] valuesToWrite) {
        return observeWrite(findCharacteristic(uuidToWrite), valuesToWrite);
    }


    @Override public Observable<GattObserveData> observeWrite(
            final BluetoothGattCharacteristic characteristicToWrite, final List<Byte> valuesToWrite) {
        return observeWrite(characteristicToWrite, Bytes.toArray(valuesToWrite));
    }


    @Override
    public Observable<GattObserveData> observeWrite(final BluetoothGattCharacteristic characteristicToWrite, final byte[] valuesToWrite) {
        return Observable.merge(writeSubject = PublishSubject.create(),
                Observable.create((Observable.OnSubscribe<GattObserveData>) subscriber -> {
                    if (isConnected()) {
                        if (valuesToWrite == null || valuesToWrite.length == 0) {
                            subscriber.onError(new GattWriteCharacteristicException(characteristicToWrite, NULL_OR_EMPTY_DATA));
                        }
                        if (characteristicToWrite != null) {
                            characteristicToWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                            characteristicToWrite.setValue(valuesToWrite);
                            bluetoothGatt.writeCharacteristic(characteristicToWrite);
                        } else {
                            subscriber.onError(UUID_NOT_FOUND);
                        }
                    } else {
                        subscriber.onError(GATT_NOT_CONNECTED);
                    }
                })).doOnSubscribe(() -> currentWriteCharacteristic = characteristicToWrite);
    }


    @Override public Observable<GattObserveData> observeNotification(
            final UUID uuidToNotification, final boolean enableNotification) {
        return observeNotification(findCharacteristic(uuidToNotification), enableNotification);
    }


    @Override public Observable<GattObserveData> observeNotification(
            final BluetoothGattCharacteristic characteristicToNotification, final boolean enableNotification) {
        return Observable.merge(notificationSubject = PublishSubject.create(),
                Observable.create((Observable.OnSubscribe<GattObserveData>) subscriber -> {
                    if (isConnected()) {
                        if (characteristicToNotification != null) {
                            bluetoothGatt.setCharacteristicNotification(characteristicToNotification, enableNotification);
                            BluetoothGattDescriptor notificationDescriptor = characteristicToNotification.getDescriptor(BluetoothGatts.CLIENT_CHARACTERISTIC_CONFIG);
                            byte[] value = enableNotification ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                            notificationDescriptor.setValue(value);
                            bluetoothGatt.writeDescriptor(notificationDescriptor);
                        } else {
                            subscriber.onError(UUID_NOT_FOUND);
                        }
                    } else {
                        subscriber.onError(GATT_NOT_CONNECTED);
                    }
                })).doOnSubscribe(() -> currentNotificationCharacteristic = characteristicToNotification);
    }


    @Override
    public Boolean isNotificationEnabled(final BluetoothGattCharacteristic characteristic) {
        if (characteristic != null && isConnected()) {
            BluetoothGattDescriptor notificationDescriptor = characteristic.getDescriptor(BluetoothGatts.CLIENT_CHARACTERISTIC_CONFIG);
            return notificationDescriptor != null && notificationDescriptor.getValue() == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        } else {
            return false;
        }
    }


    @Override
    public Observable<GattObserveData> observeIndication(final UUID uuidToIndication) {
        return observeIndication(findCharacteristic(uuidToIndication));
    }


    @Override public Observable<GattObserveData> observeIndication(
            final BluetoothGattCharacteristic characteristicToIndication) {
        return Observable.merge(indicationSubject = PublishSubject.create(),
                Observable.create((Observable.OnSubscribe<GattObserveData>) subscriber -> {
                    if (isConnected()) {
                        if (characteristicToIndication != null) {
                            bluetoothGatt.setCharacteristicNotification(characteristicToIndication, true);
                            BluetoothGattDescriptor indicationDescriptor = characteristicToIndication.getDescriptor(BluetoothGatts.CLIENT_CHARACTERISTIC_CONFIG);
                            indicationDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                            bluetoothGatt.writeDescriptor(indicationDescriptor);
                        } else {
                            subscriber.onError(UUID_NOT_FOUND);
                        }
                    } else {
                        subscriber.onError(GATT_NOT_CONNECTED);
                    }
                })).doOnSubscribe(() -> currentIndicationCharacteristic = characteristicToIndication);
    }


    @Override
    public Boolean isIndicationEnabled(final BluetoothGattCharacteristic characteristic) {
        if (characteristic != null && isConnected()) {
            BluetoothGattDescriptor indicationDescriptor = characteristic.getDescriptor(BluetoothGatts.CLIENT_CHARACTERISTIC_CONFIG);
            return indicationDescriptor != null && indicationDescriptor.getValue() == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        } else {
            return false;
        }
    }


    @Override
    public BluetoothGattCharacteristic findCharacteristic(final UUID uuid) {
        for (BluetoothGattService service : bluetoothGatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (characteristic.getUuid().equals(uuid)) {
                    return characteristic;
                }
            }
        }
        return null;
    }


    @Override public BluetoothGatt getGatt() {
        return bluetoothGatt;
    }


    @Override public BleDevice getBleDevice() {
        return bleDevice;
    }


    private boolean isGattStatusSuccess(final int status) {
        return status == BluetoothGatt.GATT_SUCCESS;
    }


    private boolean isCharacteristicAvailable(final BluetoothGattCharacteristic callbackCharacteristic,
                                              final BluetoothGattCharacteristic currentCharacteristic) {
        return (currentCharacteristic != null && callbackCharacteristic.equals(currentCharacteristic));
    }


    class GattManagerCallBack extends BluetoothGattCallback {
        @Override public void onConnectionStateChange(
                final BluetoothGatt bluetoothGatt, final int status, final int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && isConnected()) {
                connectionSubject.onNext(true);
            } else {
                connectionSubject.onNext(false);
                connectionSubject.onCompleted();
                closeGatt();
            }
        }


        @Override public void onReadRemoteRssi(
                final BluetoothGatt bluetoothGatt, final int rssi, final int status) {
            if (isGattStatusSuccess(status)) {
                rssiSubject.onNext(rssi);
            } else {
                rssiSubject.onError(new GattRssiException(status));
            }
        }


        @Override public void onServicesDiscovered(
                final BluetoothGatt bluetoothGatt, final int status) {
            if (isGattStatusSuccess(status)) {
                serviceSubject.onNext(bluetoothGatt.getServices());
                serviceSubject.onCompleted();
            } else {
                serviceSubject.onError(new GattResourceNotDiscoveredException(NONE_SERVICES));
            }
        }


        @Override public void onCharacteristicRead(
                final BluetoothGatt bluetoothGatt, final BluetoothGattCharacteristic characteristic, final int status) {
            if (isGattStatusSuccess(status)) {
                readSubject.onNext(characteristic);
                readSubject.onCompleted();
            } else {
                readSubject.onError(new GattReadCharacteristicException(
                        characteristic, STATUS_RESULT_FAIL, status));
            }
        }


        @Override public void onCharacteristicWrite(
                final BluetoothGatt bluetoothGatt, final BluetoothGattCharacteristic characteristic, final int status) {
            if (isGattStatusSuccess(status)) {
                writeSubject.onNext(new GattObserveData(characteristic, STATE_ON_START));
            } else {
                writeSubject.onError(
                        new GattWriteCharacteristicException(characteristic, STATUS_RESULT_FAIL, status));
            }
        }


        @Override public void onCharacteristicChanged(
                final BluetoothGatt bluetoothGatt, final BluetoothGattCharacteristic characteristic) {
            if (writeSubject != null && currentWriteCharacteristic != null && characteristic.equals(currentNotificationCharacteristic)) {
                writeSubject.onNext(new GattObserveData(characteristic, STATE_ON_NEXT));
            }
            if (notificationSubject != null && isCharacteristicAvailable(characteristic, currentNotificationCharacteristic)) {
                notificationSubject.onNext(new GattObserveData(currentNotificationCharacteristic, STATE_ON_NEXT));
            }
            if (indicationSubject != null && isCharacteristicAvailable(characteristic, currentIndicationCharacteristic)) {
                indicationSubject.onNext(new GattObserveData(currentIndicationCharacteristic, STATE_ON_NEXT));
            }
        }


        @Override
        public void onDescriptorWrite(final BluetoothGatt bluetoothGatt, final BluetoothGattDescriptor descriptor, final int status) {
            BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
            if (isGattStatusSuccess(status)) {
                if (isCharacteristicAvailable(characteristic, currentNotificationCharacteristic)) {
                    notificationSubject.onNext(new GattObserveData(currentNotificationCharacteristic, STATE_ON_START));
                }
                if (isCharacteristicAvailable(characteristic, currentIndicationCharacteristic)) {
                    indicationSubject.onNext(new GattObserveData(currentIndicationCharacteristic, STATE_ON_START));
                }
            } else {
                GattNotificationCharacteristicException exception = new GattNotificationCharacteristicException(descriptor, DESCRIPTION_WRITE_FAIL, status);
                if (isCharacteristicAvailable(characteristic, currentNotificationCharacteristic)) {
                    notificationSubject.onError(exception);
                }
                if (isCharacteristicAvailable(characteristic, currentIndicationCharacteristic)) {
                    indicationSubject.onError(exception);
                }
            }
        }
    }
}