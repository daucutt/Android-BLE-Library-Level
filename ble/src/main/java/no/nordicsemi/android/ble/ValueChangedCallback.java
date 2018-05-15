package no.nordicsemi.android.ble;

import android.bluetooth.BluetoothDevice;
import android.os.ConditionVariable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.callback.ReadProgressCallback;
import no.nordicsemi.android.ble.callback.profile.ProfileReadResponse;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.data.DataMerger;
import no.nordicsemi.android.ble.data.DataStream;
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException;
import no.nordicsemi.android.ble.exception.InvalidDataException;

@SuppressWarnings({"unused", "WeakerAccess", "UnusedReturnValue"})
public class ValueChangedCallback {
	private final BleManager<?> bleManager;
	private final ConditionVariable syncLock;
	private ReadProgressCallback progressCallback;
	private DataReceivedCallback valueCallback;
	private DataMerger dataMerger;
	private DataStream buffer;
	private int count = 0;
	private boolean deviceDisconnected;

	/**
	 * A handler that provides a request that will be executed before setting the value changed
	 * handler. This may be useful when you need to perform an action that will trigger a notification
	 * or indication (i.e. send an Op Code), but you want to do those synchronously.
	 * Waiting for a value change before sending the request would never end, and writing a request
	 * before setting the handler could end with a race condition and the notification could be
	 * received too early.
	 */
	public interface TriggerHandler {
		/**
		 * Returns a request that must be executed before the value change is expected.
		 * This is usually a write request that will trigger the notification or indication.
		 *
		 * @return the request
		 */
		@Nullable
		Request getRequest();
	}

	ValueChangedCallback(final BleManager<?> manager) {
		bleManager = manager;
		syncLock = new ConditionVariable(true);
	}

	/**
	 * Sets the asynchronous data callback that will be called whenever a notification or
	 * an indication is received on given characteristic.
	 * <p>
	 * This callback is ignored when synchronous call is made using {@link #await(Class, int)}
	 * or any of variants.
	 * </p>
	 *
	 * @param callback the data callback.
	 * @return the request
	 */
	@NonNull
	public ValueChangedCallback with(@NonNull final DataReceivedCallback callback) {
		this.valueCallback = callback;
		return this;
	}

	/**
	 * Adds a merger that will be used to merge multiple packets into a single Data.
	 * The merger may modify each packet if necessary.
	 *
	 * @return the callback
	 */
	@NonNull
	public ValueChangedCallback merge(final @NonNull DataMerger merger) {
		this.dataMerger = merger;
		this.progressCallback = null;
		return this;
	}

	/**
	 * Adds a merger that will be used to merge multiple packets into a single Data.
	 * The merger may modify each packet if necessary.
	 *
	 * @return the callback
	 */
	@NonNull
	public ValueChangedCallback merge(final @NonNull DataMerger merger, final @NonNull ReadProgressCallback callback) {
		this.dataMerger = merger;
		this.progressCallback = callback;
		return this;
	}

	/**
	 * Synchronously waits for a notification or an indication on the requested characteristic.
	 * <p>
	 * This method may not be called from the main (UI) thread.
	 * </p>
	 * <p>
	 * The value of returned notification or indication is ignored.
	 * </p>
	 *
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 */
	@SuppressWarnings("ConstantConditions")
	public void await() throws DeviceDisconnectedException {
		awaitAfter((TriggerHandler) null, null);
	}

	/**
	 * Synchronously waits for a notification or an indication on the requested characteristic,
	 * for at most given number of milliseconds.
	 * <p>
	 * This method may not be called from the main (UI) thread.
	 * </p>
	 * <p>
	 * The value of returned notification or indication is ignored.
	 * </p>
	 *
	 * @param timeout optional timeout in milliseconds
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws InterruptedException        thrown when the timeout occurred before the characteristic
	 *                                     value has changed.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 */
	@SuppressWarnings("ConstantConditions")
	public void await(final int timeout) throws InterruptedException, DeviceDisconnectedException {
		awaitAfter((TriggerHandler) null, null, timeout);
	}

	/**
	 * Synchronously waits for a notification or an indication on the requested characteristic.
	 * <p>
	 * This method may not be called from the main (UI) thread.
	 * </p>
	 *
	 * @param responseClass the response class. This class will be instantiate, therefore it has to have
	 *                      a default constructor.
	 * @return the object received with a notification or indication
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 */
	@SuppressWarnings({"NullableProblems", "ConstantConditions"})
	@NonNull
	public <E extends DataReceivedCallback> E await(final @NonNull Class<E> responseClass)
			throws DeviceDisconnectedException {
		return awaitAfter((TriggerHandler) null, responseClass);
	}

	/**
	 * Synchronously waits for a notification or an indication on the requested characteristic.
	 * <p>
	 * This method may not be called from the main (UI) thread.
	 * </p>
	 *
	 * @param timeout       optional timeout in milliseconds
	 * @param responseClass the response class. This class will be instantiate, therefore it has to have
	 *                      a default constructor.
	 * @return the object received with a notification or indication
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws InterruptedException        thrown when the timeout occurred before the characteristic
	 *                                     value has changed.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 */
	@SuppressWarnings({"NullableProblems", "ConstantConditions"})
	@NonNull
	public <E extends DataReceivedCallback> E await(final @NonNull Class<E> responseClass,
													final int timeout)
			throws InterruptedException, DeviceDisconnectedException {
		return awaitAfter((TriggerHandler) null, responseClass, timeout);
	}

	/**
	 * This method works just like {@link #awaitAfter(TriggerHandler, Class)},
	 * but you may directly return the trigger request. It will wait without a timeout until
	 * a notification/indication is received, or the device disconnects.
	 *
	 * @param trigger       a request that triggers the notification or indication.
	 * @param responseClass the response class. This class will be instantiate, therefore it has to
	 *                      have a default constructor.
	 * @return the object received with a notification or indication
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 * @see #awaitAfter(TriggerHandler, Class)
	 * @see #awaitAfter(Request, Class, int)
	 */
	@SuppressWarnings("NullableProblems")
	@NonNull
	public <E extends DataReceivedCallback> E awaitAfter(final @NonNull Request<?> trigger,
														 final @NonNull Class<E> responseClass)
			throws DeviceDisconnectedException {
		return awaitAfter(() -> trigger, responseClass);
	}

	/**
	 * This method works just like {@link #awaitAfter(TriggerHandler, Class, int)},
	 * but will wait without a timeout until a notification/indication is received, or the
	 * device disconnects.
	 *
	 * @param trigger       a handler that returns a request that triggers the notification or
	 *                      indication.
	 * @param responseClass the response class. This class will be instantiate, therefore it has to
	 *                      have a default constructor.
	 * @return the object received with a notification or indication
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 * @see #awaitAfter(Request, Class)
	 * @see #awaitAfter(TriggerHandler, Class, int)
	 */
	@SuppressWarnings("NullableProblems")
	@NonNull
	public <E extends DataReceivedCallback> E awaitAfter(final @NonNull TriggerHandler trigger,
														 final @NonNull Class<E> responseClass)
			throws DeviceDisconnectedException {
		try {
			return awaitAfter(trigger, responseClass, 0);
		} catch (final InterruptedException e) {
			// never happen
			throw new IllegalStateException("This should never happen");
		}
	}

	/**
	 * This method works just like {@link #awaitAfter(TriggerHandler, Class, int)},
	 * but you may directly specify the trigger request. Before binding the value change callback
	 * with given characteristic this method will enqueue the trigger request.
	 * This is to ensure that the value change handler is bound to the characteristic before the
	 * trigger request is performed. Otherwise, a race condition may occur when either the triggered
	 * notification is received before setting the callback, or the callback awaits a notification
	 * but the trigger command has never been sent.
	 * <p>
	 * Example of synchronous usage:
	 * <pre>
	 *     // First, enable notifications
	 *     enableNotifications(characteristic).await();
	 *
	 *     // Then, bind notification callback and write op code.
	 *     SomeResponse response = setNotificationCallback(characteristic)
	 *          .awaitAfter(writeCharacteristic(controlPoint, OP_CODE),
	 *                      SomeResponse.class, 1000);
	 * </pre>
	 * </p>
	 *
	 * @param trigger       a request that triggers the notification or indication.
	 * @param responseClass the response class. This class will be instantiate, therefore it has to
	 *                      have a default constructor.
	 * @param timeout       optional timeout in milliseconds
	 * @return the object received with a notification or indication
	 * @throws InterruptedException        thrown when the timeout occurred before the characteristic
	 *                                     value has changed.
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 * @see #awaitAfter(TriggerHandler, Class, int)
	 */
	@SuppressWarnings({"NullableProblems", "ConstantConditions"})
	@NonNull
	public <E extends DataReceivedCallback> E awaitAfter(final @NonNull Request<?> trigger,
														 final @NonNull Class<E> responseClass,
														 final int timeout)
			throws InterruptedException, DeviceDisconnectedException {
		return awaitAfter(() -> trigger, responseClass, timeout);
	}

	/**
	 * Synchronously waits for a notification or an indication on the requested characteristic,
	 * for at most given number of milliseconds. Before binding the value change callback
	 * with given characteristic this method will enqueue the request returned by the trigger.
	 * This is to ensure that the value change handler is bound to the characteristic before the
	 * trigger request is performed. Otherwise, a race condition may occur when either the triggered
	 * notification is received before setting the callback, or the callback awaits a notification
	 * but the trigger command has never been sent.
	 * <p>
	 * This method may not be called from the main (UI) thread.
	 * </p>
	 * <p>
	 * Example of synchronous usage:
	 * <pre>
	 *     // First, enable notifications
	 *     enableNotifications(characteristic).await();
	 *
	 *     // Then, bind notification callback and write op code.
	 *     SomeResponse response = setNotificationCallback(characteristic)
	 *          .awaitAfter(() -> {
	 *                // Do something here
	 *                return writeCharacteristic(controlPoint, OP_CODE);
	 *          }, SomeResponse.class, 1000);
	 * </pre>
	 * </p>
	 *
	 * @param trigger       a handler that returns a request that triggers the notification or
	 *                      indication.
	 * @param responseClass the response class. This class will be instantiate, therefore it has to
	 *                      have a default constructor.
	 * @param timeout       optional timeout in milliseconds
	 * @return the object received with a notification or indication
	 * @throws InterruptedException        thrown when the timeout occurred before the characteristic
	 *                                     value has changed.
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 */
	@SuppressWarnings({"NullableProblems", "ConstantConditions"})
	@NonNull
	public <E extends DataReceivedCallback> E awaitAfter(final @NonNull TriggerHandler trigger,
														 final @NonNull Class<E> responseClass,
														 final int timeout)
			throws InterruptedException, DeviceDisconnectedException {
		Request.assertNotMainThread();

		final DataReceivedCallback vc = valueCallback;
		try {
			E response = null;
			if (responseClass != null)
				response = responseClass.newInstance();
			syncLock.close();
			with(response);

			deviceDisconnected = !bleManager.isConnected();

			// Ensure the trigger request it done after the callback has been set
			if (!deviceDisconnected && trigger != null) {
				final Request<?> request = trigger.getRequest();
				if (request != null) {
					bleManager.enqueue(request);
				}
			}
			// Wait for value change event
			if (!deviceDisconnected && !syncLock.block(timeout)) {
				throw new InterruptedException();
			}
			if (deviceDisconnected) {
				syncLock.open();
				throw new DeviceDisconnectedException();
			}
			return response;
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException("Couldn't instantiate " + responseClass.getCanonicalName()
					+ " class. Is the default constructor accessible?");
		} catch (InstantiationException e) {
			throw new IllegalArgumentException("Couldn't instantiate " + responseClass.getCanonicalName()
					+ " class. Does it have a default constructor with no arguments?");
		} finally {
			valueCallback = vc;
		}
	}

	/**
	 * Similar to {@link #await(Class)}, but if the response class extends {@link ProfileReadResponse}
	 * and the received response is invalid, an exception is thrown. This allows to keep all
	 * error handling in one place.
	 *
	 * @param responseClass the result class. This class will be instantiate, therefore it has to have
	 *                      a default constructor.
	 * @param <E>           a response class that extends {@link ProfileReadResponse}.
	 * @return object with a valid response
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws InvalidDataException        exception thrown when the data received were invalid and
	 *                                     {@link ProfileReadResponse#onInvalidDataReceived(BluetoothDevice, Data)}
	 *                                     was called during parsing them.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 */
	@SuppressWarnings("ConstantConditions")
	@NonNull
	public <E extends ProfileReadResponse> E awaitValid(final @NonNull Class<E> responseClass)
			throws InvalidDataException, DeviceDisconnectedException {
		try {
			return awaitValidAfter((TriggerHandler) null, responseClass, 0);
		} catch (final InterruptedException e) {
			// never happen
			throw new IllegalStateException("This should never happen");
		}
	}

	/**
	 * Similar to {@link #await(Class)}, but if the response class extends {@link ProfileReadResponse}
	 * and the received response is invalid, an exception is thrown. This allows to keep all
	 * error handling in one place.
	 *
	 * @param responseClass the result class. This class will be instantiate, therefore it has to have
	 *                      a default constructor.
	 * @param <E>           a response class that extends {@link ProfileReadResponse}.
	 * @return object with a valid response
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws InterruptedException        thrown when the timeout occurred before the characteristic
	 *                                     value has changed.
	 * @throws InvalidDataException        exception thrown when the data received were invalid and
	 *                                     {@link ProfileReadResponse#onInvalidDataReceived(BluetoothDevice, Data)}
	 *                                     was called during parsing them.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 */
	@SuppressWarnings("ConstantConditions")
	@NonNull
	public <E extends ProfileReadResponse> E awaitValid(final @NonNull Class<E> responseClass,
														final int timeout)
			throws InterruptedException, InvalidDataException, DeviceDisconnectedException {
		return awaitValidAfter((TriggerHandler) null, responseClass, timeout);
	}

	/**
	 * Same as {@link #awaitAfter(Request, Class)}, but if the response class extends
	 * {@link ProfileReadResponse} and the received response is not valid, this method will thrown
	 * an exception instead of just returning a response with {@link ProfileReadResponse#isValid()}
	 * returning false.
	 *
	 * @param trigger       a request that triggers the notification or indication.
	 * @param responseClass the response class. This class will be instantiate, therefore it has to
	 *                      have a default constructor.
	 * @return the object received with a notification or indication
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 * @see #awaitValidAfter(TriggerHandler, Class)
	 */
	@NonNull
	public <E extends ProfileReadResponse> E awaitValidAfter(final @NonNull Request<?> trigger,
															 final @NonNull Class<E> responseClass)
			throws InvalidDataException, DeviceDisconnectedException {
		return awaitValidAfter(() -> trigger, responseClass);
	}

	/**
	 * Same as {@link #awaitAfter(TriggerHandler, Class)}, but if the response class extends
	 * {@link ProfileReadResponse} and the received response is not valid, this method will thrown
	 * an exception instead of just returning a response with {@link ProfileReadResponse#isValid()}
	 * returning false.
	 *
	 * @param trigger       a handler that returns a request that triggers the notification or
	 *                      indication.
	 * @param responseClass the result class. This class will be instantiate, therefore it has to have
	 *                      a default constructor.
	 * @param <E>           a response class that extends {@link ProfileReadResponse}.
	 * @return object with a valid response
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws InvalidDataException        exception thrown when the data received were invalid and
	 *                                     {@link ProfileReadResponse#onInvalidDataReceived(BluetoothDevice, Data)}
	 *                                     was called during parsing them.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 * @see #awaitValidAfter(Request, Class)
	 */
	@NonNull
	public <E extends ProfileReadResponse> E awaitValidAfter(final @NonNull TriggerHandler trigger,
															 final @NonNull Class<E> responseClass)
			throws InvalidDataException, DeviceDisconnectedException {
		try {
			return awaitValidAfter(trigger, responseClass, 0);
		} catch (final InterruptedException e) {
			// never happen
			throw new IllegalStateException("This should never happen");
		}
	}

	/**
	 * Same as {@link #awaitAfter(Request, Class, int)}, but if the response class extends
	 * {@link ProfileReadResponse} and the received response is not valid, this method will thrown
	 * an exception instead of just returning a response with {@link ProfileReadResponse#isValid()}
	 * returning false.
	 *
	 * @param trigger       a request that triggers the notification or indication.
	 * @param responseClass the response class. This class will be instantiate, therefore it has to
	 *                      have a default constructor.
	 * @param timeout       optional timeout in milliseconds
	 * @return the object received with a notification or indication
	 * @throws InterruptedException        thrown when the timeout occurred before the characteristic
	 *                                     value has changed.
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 * @see #awaitValidAfter(TriggerHandler, Class, int)
	 */
	@NonNull
	public <E extends ProfileReadResponse> E awaitValidAfter(final @NonNull Request<?> trigger,
															 final @NonNull Class<E> responseClass,
															 final int timeout)
			throws InterruptedException, InvalidDataException, DeviceDisconnectedException {
		return awaitValidAfter(() -> trigger, responseClass, timeout);
	}

	/**
	 * Same as {@link #awaitAfter(TriggerHandler, Class, int)}, but if the response class extends
	 * {@link ProfileReadResponse} and the received response is not valid, this method will thrown
	 * an exception instead of just returning a response with {@link ProfileReadResponse#isValid()}
	 * returning false.
	 *
	 * @param trigger       a handler that returns a request that triggers the notification or
	 *                      indication.
	 * @param responseClass the result class. This class will be instantiate, therefore it has to have
	 *                      a default constructor.
	 * @param timeout       optional timeout in milliseconds.
	 * @param <E>           a response class that extends {@link ProfileReadResponse}.
	 * @return object with a valid response
	 * @throws IllegalStateException       thrown when you try to call this method from the main (UI)
	 *                                     thread.
	 * @throws InterruptedException        thrown when the timeout occurred before the characteristic
	 *                                     value has changed.
	 * @throws InvalidDataException        exception thrown when the data received were invalid and
	 *                                     {@link ProfileReadResponse#onInvalidDataReceived(BluetoothDevice, Data)}
	 *                                     was called during parsing them.
	 * @throws DeviceDisconnectedException thrown when the device disconnected before the
	 *                                     notification or indication was received.
	 * @see #awaitValidAfter(Request, Class, int)
	 */
	@SuppressWarnings({"ConstantConditions", "NullableProblems"})
	@NonNull
	public <E extends ProfileReadResponse> E awaitValidAfter(final @NonNull TriggerHandler trigger,
															 final @NonNull Class<E> responseClass,
															 final int timeout)
			throws InterruptedException, InvalidDataException, DeviceDisconnectedException {
		final E response = awaitAfter(trigger, responseClass, timeout);
		if (response != null && !response.isValid()) {
			throw new InvalidDataException(response);
		}
		return response;
	}

	ValueChangedCallback free() {
		valueCallback = null;
		dataMerger = null;
		progressCallback = null;
		deviceDisconnected = false;
		buffer = null;
		count = 0;
		return this;
	}

	void notifyValueChanged(final BluetoothDevice device, final byte[] value) {
		// With no value callback there is no need for any merging
		if (valueCallback == null) {
			syncLock.open();
			return;
		}

		if (dataMerger == null) {
			valueCallback.onDataReceived(device, new Data(value));
			syncLock.open();
		} else {
			if (progressCallback != null)
				progressCallback.onPacketReceived(device, value, count);
			if (buffer == null)
				buffer = new DataStream();
			if (dataMerger.merge(buffer, value, count++)) {
				valueCallback.onDataReceived(device, buffer.toData());
				buffer = null;
				count = 0;
				syncLock.open();
			} // else
			// wait for more packets to be merged
		}
	}

	void notifyDeviceDisconnected(final BluetoothDevice device) {
		deviceDisconnected = true;
		syncLock.open();
	}
}