
package com.rainbow.kam.ble_gatt_manager.manager;

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
import android.text.TextUtils;
import android.util.Log;

import com.google.common.primitives.Bytes;
import com.rainbow.kam.ble_gatt_manager.data.BleDevice;
import com.rainbow.kam.ble_gatt_manager.exceptions.details.ConnectedFailException;
import com.rainbow.kam.ble_gatt_manager.exceptions.details.DisconnectedFailException;
import com.rainbow.kam.ble_gatt_manager.exceptions.details.GattResourceNotDiscoveredException;
import com.rainbow.kam.ble_gatt_manager.exceptions.details.NotificationCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.exceptions.details.ReadCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.exceptions.details.RssiMissException;
import com.rainbow.kam.ble_gatt_manager.exceptions.details.WriteCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.util.BluetoothGatts;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

/**
 * Created by kam6512 on 2015-10-29.
 */
public class GattManager implements GattManagerObserves {

    private final static String TAG = GattManager.class.getSimpleName();

    private final static long RSSI_UPDATE_TIME_INTERVAL = 3;
    private final Application app;

    private BleDevice device;
    private String deviceAddress;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothDevice bluetoothDevice;

    private BluetoothGattDescriptor notificationDescriptor;

    private final PublishSubject<Boolean> connectionSubject = PublishSubject.create();
    private final PublishSubject<List<BluetoothGattService>> serviceSubject = PublishSubject.create();
    private final PublishSubject<BluetoothGattCharacteristic> readSubject = PublishSubject.create();
    private final PublishSubject<BluetoothGattCharacteristic> writeSubject = PublishSubject.create();
    private final PublishSubject<BluetoothGattCharacteristic> notificationSubject = PublishSubject.create();
    private final PublishSubject<Integer> rssiSubject = PublishSubject.create();

    private Subscription rssiTimerSubscription;

    private static final GattResourceNotDiscoveredException UUID_CHARACTERLESS;

    static {
        UUID_CHARACTERLESS = new GattResourceNotDiscoveredException("CHECK UUID / CHARACTERISTIC");
    }

    @Inject public GattManager(final Application application) {
        app = application;
        setBluetooth();
    }


    private void setBluetooth() {
        bluetoothManager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }


    public BluetoothGatt getGatt() {
        return bluetoothGatt;
    }


    public BleDevice getBleDevice() {
        return device;
    }


    @Override public Observable<Boolean> observeConnection() {
        return observeConnection(device);
    }


    @Override public Observable<Boolean> observeConnection(
            BleDevice bleDevice) {
        return Observable.merge(connectionSubject,
                Observable.create(subscriber -> {
                    if (bleDevice == null) {
                        subscriber.onError(new NullPointerException("BleDevice is Null"));
                    }

                    device = bleDevice;
                    deviceAddress = device.getAddress();


                    if (TextUtils.isEmpty(deviceAddress)) {
                        subscriber.onError(new ConnectedFailException(deviceAddress, "Address is not available"));
                    }

                    if (bluetoothManager == null || bluetoothAdapter == null) {
                        setBluetooth();
                    }

                    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                        subscriber.onError(new ConnectedFailException(deviceAddress, "Adapter is not available or disabled"));
                    }

                    bluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress);

                    if (bluetoothDevice == null) {
                        subscriber.onError(new ConnectedFailException(deviceAddress, "this Device not Supported BLE"));
                    }


                    if (app == null) {
                        subscriber.onError(new NullPointerException("Application is Null"));
                    }

                    if (isConnected()) {
                        subscriber.onNext(true);
                    } else {
                        bluetoothGatt = bluetoothDevice.connectGatt(app, false, bluetoothGattCallback);
                    }
                }))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread());
    }


    public boolean isConnected() {
        return bluetoothManager.getConnectionState(device.getDevice(),
                BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED;
    }


    public void disconnect() {
        if (bluetoothGatt != null && bluetoothAdapter != null) {
            if (bluetoothAdapter.isEnabled() && isConnected()) {
                bluetoothGatt.disconnect();
            } else {
                bluetoothGatt.close();
                connectionSubject.onError(new DisconnectedFailException(device.getAddress(), "Device already Disconnected"));
            }
        } else {
            connectionSubject.onError(new DisconnectedFailException(device.getAddress(), "Gatt / Adapter is not available or disabled"));
        }
    }


    private void closeGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
        }
        disableAllGattSubject();
    }


    private void disableAllGattSubject() {
        if (connectionSubject != null) {
            connectionSubject.onCompleted();
        }
        if (serviceSubject != null) {
            serviceSubject.onCompleted();
        }
        if (readSubject != null) {
            readSubject.onCompleted();
        }
        if (writeSubject != null) {
            writeSubject.onCompleted();
        }
        if (notificationSubject != null) {
            notificationSubject.onCompleted();
        }
        if (rssiSubject != null) {
            rssiSubject.onCompleted();
        }
        if (rssiTimerSubscription != null) {
            rssiTimerSubscription.unsubscribe();
        }
    }


    @Override public Observable<Integer> observeRssi() {
        return Observable.merge(rssiSubject, Observable.create(readRssiValue())
                .doOnSubscribe(() -> rssiTimerSubscription.unsubscribe())
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread()));
    }


    private Observable.OnSubscribe<Integer> readRssiValue() {
        return subscriber -> {
            if (isConnected()) {
                rssiTimerSubscription = Observable.interval(RSSI_UPDATE_TIME_INTERVAL, TimeUnit.SECONDS)
                        .subscribe(aLong -> bluetoothGatt.readRemoteRssi());
            } else {
                subscriber.unsubscribe();
            }
        };
    }


    @Override
    public Observable<List<BluetoothGattService>> observeDiscoverService() {
        return Observable.merge(serviceSubject,
                Observable.create(subscriber -> bluetoothGatt.discoverServices()))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread());
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeBattery() {
        return observeRead(BluetoothGatts.BATTERY_CHARACTERISTIC_UUID);
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeRead(UUID uuidToRead) {
        return observeRead(findCharacteristic(uuidToRead));
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeRead(BluetoothGattCharacteristic characteristicToRead) {
        return Observable.merge(readSubject,
                Observable.create(subscriber -> {
                    if (characteristicToRead == null) {
                        subscriber.onError(UUID_CHARACTERLESS);
                    }
                    bluetoothGatt.readCharacteristic(characteristicToRead);
                }))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread());
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeWrite(UUID uuidToWrite, List<Byte> valuesToWrite) {
        return observeWrite(findCharacteristic(uuidToWrite), valuesToWrite);
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeWrite(UUID uuidToWrite, byte[] valuesToWrite) {
        return observeWrite(findCharacteristic(uuidToWrite), valuesToWrite);
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeWrite(BluetoothGattCharacteristic characteristicToWrite, List<Byte> valuesToWrite) {
        return observeWrite(characteristicToWrite, Bytes.toArray(valuesToWrite));
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeWrite(BluetoothGattCharacteristic characteristicToWrite, byte[] valuesToWrite) {
        return Observable.merge(writeSubject,
                Observable.create(subscriber -> {
                    if (characteristicToWrite == null) {
                        subscriber.onError(UUID_CHARACTERLESS);
                    }
                    if (valuesToWrite.length == 0) {
                        subscriber.onError(new WriteCharacteristicException(characteristicToWrite, "data is Null or Empty"));
                    }
                    characteristicToWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    characteristicToWrite.setValue(valuesToWrite);
                    bluetoothGatt.writeCharacteristic(characteristicToWrite);
                }))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread());
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeNotification(UUID uuidToNotification, boolean enableNotification) {
        return observeNotification(findCharacteristic(uuidToNotification), enableNotification);
    }


    @Override
    public Observable<BluetoothGattCharacteristic> observeNotification(BluetoothGattCharacteristic characteristicToNotification, boolean enableNotification) {
        return Observable.merge(notificationSubject,
                Observable.create(subscriber -> {
                    if (characteristicToNotification == null) {
                        subscriber.onError(UUID_CHARACTERLESS);
                    }
                    bluetoothGatt.setCharacteristicNotification(characteristicToNotification, enableNotification);
                    notificationDescriptor = characteristicToNotification.getDescriptor(BluetoothGatts.CLIENT_CHARACTERISTIC_CONFIG);
                    byte[] value = enableNotification ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    notificationDescriptor.setValue(value);
                    bluetoothGatt.writeDescriptor(notificationDescriptor);
                }))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread());
    }


    public Observable<Boolean> isNotificationEnabled(BluetoothGattCharacteristic characteristic) {
        return Observable.create(subscriber -> {
            if (characteristic == null) {
                subscriber.onError(UUID_CHARACTERLESS);
            }
            byte[] descriptorValue = characteristic.getDescriptor(BluetoothGatts.CLIENT_CHARACTERISTIC_CONFIG).getValue();
            boolean isEnabled = (descriptorValue == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            subscriber.onNext(isEnabled);
        });
    }


    public BluetoothGattCharacteristic findCharacteristic(UUID uuid) {
        for (BluetoothGattService service : bluetoothGatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (characteristic.getUuid().equals(uuid)) {
                    return characteristic;
                }
            }
        }
        return null;
    }


    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt,
                                            int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionSubject.onNext(true);
            } else {
                connectionSubject.onNext(false);
                closeGatt();
            }
        }


        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                serviceSubject.onNext(bluetoothGatt.getServices());
            } else {
                serviceSubject.onError(new GattResourceNotDiscoveredException("ServicesDiscovered FAIL"));
            }
        }


        @Override
        public void onCharacteristicRead(BluetoothGatt bluetoothGatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readSubject.onNext(characteristic);
            } else {
                readSubject.onError(
                        new ReadCharacteristicException(characteristic, "Check Gatt Service Available or Device Connection!", status));
            }
        }


        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                                            BluetoothGattCharacteristic characteristic) {
            writeSubject.onNext(characteristic);
        }


        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CharacteristicWrite SUCCESS " + characteristic.getUuid().toString());
            } else {
                writeSubject.onError(
                        new WriteCharacteristicException(characteristic, "Check Gatt Service Available or Device Connection!", status));
            }
        }


        @Override
        public void onReadRemoteRssi(BluetoothGatt bluetoothGatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                rssiSubject.onNext(rssi);
            } else {
                rssiSubject.onError(new RssiMissException(status));
            }
        }


        @Override
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.equals(notificationDescriptor)) {
                notificationSubject.onNext(descriptor.getCharacteristic());
            } else {
                notificationSubject.onError(
                        new NotificationCharacteristicException(descriptor.getCharacteristic(), descriptor, "DescriptorWrite FAIL", status));
            }
        }

    };
}
