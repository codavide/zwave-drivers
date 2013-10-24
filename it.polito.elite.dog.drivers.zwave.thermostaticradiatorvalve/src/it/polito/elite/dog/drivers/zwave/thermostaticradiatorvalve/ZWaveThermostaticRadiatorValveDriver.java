/*
 * Dog  - Z-Wave
 * 
 * Copyright 2013 Dario Bonino 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package it.polito.elite.dog.drivers.zwave.thermostaticradiatorvalve;

import it.polito.elite.dog.core.library.model.ControllableDevice;
import it.polito.elite.dog.core.library.model.DeviceCostants;
import it.polito.elite.dog.core.library.model.devicecategory.ThermostaticRadiatorValve;
import it.polito.elite.dog.core.library.model.notification.core.ClockTimeNotification;
import it.polito.elite.dog.core.library.util.LogHelper;
import it.polito.elite.dog.drivers.zwave.gateway.ZWaveGatewayDriver;
import it.polito.elite.dog.drivers.zwave.network.info.ZWaveInfo;
import it.polito.elite.dog.drivers.zwave.network.interfaces.ZWaveNetwork;
import it.polito.elite.dog.drivers.zwave.thermostaticradiatorvalve.ZWaveThermostaticRadiatorValveInstance;

import java.io.File;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.device.Device;
import org.osgi.service.device.Driver;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

/**
 * @author bonino
 * 
 */
public class ZWaveThermostaticRadiatorValveDriver implements Driver,
		ManagedService, EventHandler
{
	// The OSGi framework context
	protected BundleContext context;

	// System logger
	LogHelper logger;

	// the log identifier, unique for the class
	public static String LOG_ID = "[ZWaveTemperatureAndHumiditySensorDriver]: ";

	// String identifier for driver id
	public static final String DRIVER_ID = "ZWave_ZWaveTemperatureAndHumiditySensorDriver_driver";

	// a reference to the network driver
	private AtomicReference<ZWaveNetwork> network;

	// a reference to the gateway driver
	private AtomicReference<ZWaveGatewayDriver> gateway;

	// milliseconds between two update of the device status, from configuration
	// file
	protected int updateTimeMillis;
	
	// the persistence store associated to this driver
	private String persistenceStoreDirectory;

	// the list of driver instances currently connected to a device
	private Vector<ZWaveThermostaticRadiatorValveInstance> connectedDrivers;

	// the registration object needed to handle the life span of this bundle in
	// the OSGi framework (it is a ServiceRegistration object for use by the
	// bundle registering the service to update the service's properties or to
	// unregister the service).
	private ServiceRegistration<?> regDriver;

	// the filter query for listening to framework events relative to the
	// to the ZWave gateway driver
	String filterQuery = String.format("(%s=%s)", Constants.OBJECTCLASS,
			ZWaveGatewayDriver.class.getName());

	// what are the on/off device categories that can match with this driver?
	private Set<String> thermostaticRadiatorValveDeviceCategories;

	public ZWaveThermostaticRadiatorValveDriver()
	{
		this.gateway = new AtomicReference<ZWaveGatewayDriver>();
		this.network = new AtomicReference<ZWaveNetwork>();
	}

	/**
	 * Handle the bundle activation
	 */
	public void activate(BundleContext bundleContext)
	{
		// init the logger
		logger = new LogHelper(bundleContext);

		// store the context
		context = bundleContext;

		// initialize the connected drivers list
		connectedDrivers = new Vector<ZWaveThermostaticRadiatorValveInstance>();

		// initialize the set of implemented device categories
		thermostaticRadiatorValveDeviceCategories = new HashSet<String>();

		// fill supported categories of device
		properFillDeviceCategories();
	}

	public void deactivate()
	{
		// remove the service from the OSGi framework
		this.unRegister();
	}

	public void addingService(ZWaveGatewayDriver gatewayDriver)
	{
		gateway.set(gatewayDriver);

		// TODO: remove!!!
		network.set(gateway.get().getNetwork());

		// this.registerDriver();

	}

	public void removedService(ZWaveGatewayDriver gatewayDriver)
	{
		if (gateway.compareAndSet(gatewayDriver, null))
			// unregisters this driver from the OSGi framework
			unRegister();
	}

	@SuppressWarnings("rawtypes")
	@Override
	public int match(ServiceReference reference) throws Exception
	{
		int matchValue = Device.MATCH_NONE;

		// get the given device category
		String deviceCategory = (String) reference
				.getProperty(DeviceCostants.DEVICE_CATEGORY);

		// get the given device manufacturer
		String manifacturer = (String) reference
				.getProperty(DeviceCostants.MANUFACTURER);

		// get the gateway to which the device is connected
		@SuppressWarnings("unchecked")
		String gateway = (String) ((ControllableDevice) context
				.getService(reference)).getDeviceDescriptor().getGateway();

		// compute the matching score between the given device and this driver
		if (deviceCategory != null)
		{
			if (manifacturer != null
					&& (gateway != null)
					&& (manifacturer.equals(ZWaveInfo.MANUFACTURER))
					&& (thermostaticRadiatorValveDeviceCategories
							.contains(deviceCategory))
					&& (this.gateway.get().isGatewayAvailable(gateway)))
			{
				matchValue = ThermostaticRadiatorValve.MATCH_MANUFACTURER
						+ ThermostaticRadiatorValve.MATCH_TYPE;
			}

		}
		return matchValue;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public String attach(ServiceReference reference) throws Exception
	{
		// get the gateway to which the device is connected
		String gateway = (String) ((ControllableDevice) context
				.getService(reference)).getDeviceDescriptor().getGateway();

		// get the corresponding end point set
		Set<String> nodeIdSet = ((ControllableDevice) context
				.getService(reference)).getDeviceDescriptor()
				.getSimpleConfigurationParams().get(ZWaveInfo.NODE_ID);

		// get the corresponding end point set
		Set<String> instanceIdSet = ((ControllableDevice) context
				.getService(reference)).getDeviceDescriptor()
				.getSimpleConfigurationParams().get(ZWaveInfo.INSTANCE_ID);

		// get the nodeId
		String sNodeID = nodeIdSet.iterator().next();

		// get the list of instances available
		HashSet<Integer> instancesId = new HashSet<Integer>();
		for (String sInstanceId : instanceIdSet)
			instancesId.add(Integer.parseInt(sInstanceId));

		// create a new driver instance
		ZWaveThermostaticRadiatorValveInstance driverInstance = new ZWaveThermostaticRadiatorValveInstance(
				network.get(),
				(ControllableDevice) context.getService(reference),
				Integer.parseInt(sNodeID), instancesId, this.gateway.get()
						.getSpecificGateway(gateway).getNodeInfo()
						.getDeviceNodeId(), this.updateTimeMillis, this.persistenceStoreDirectory, this.context);

		((ControllableDevice) context.getService(reference))
				.setDriver(driverInstance);

		synchronized (connectedDrivers)
		{
			connectedDrivers.add(driverInstance);
		}

		return null;
	}

	/**
	 * Registers this driver in the OSGi framework, making its services
	 * available to all the other bundles living in the same or in connected
	 * frameworks.
	 */
	private void registerDriver()
	{
		if ((gateway.get() != null) && (network.get() != null)
				&& (this.context != null) && (this.regDriver == null))
		{
			// create a new property object describing this driver
			Hashtable<String, Object> propDriver = new Hashtable<String, Object>();
			// add the id of this driver to the properties
			propDriver.put(DeviceCostants.DRIVER_ID, DRIVER_ID);
			// register this driver in the OSGi framework
			regDriver = context.registerService(Driver.class.getName(), this,
					propDriver);
		}
	}

	/**
	 * Handle the bundle de-activation
	 */
	protected void unRegister()
	{
		// TODO DETACH allocated Drivers
		if (regDriver != null)
		{
			regDriver.unregister();
			regDriver = null;
		}
	}

	/**
	 * Fill a set with all the device categories whose devices can match with
	 * this driver. Automatically retrieve the device categories list by reading
	 * the implemented interfaces of its DeviceDriverInstance class bundle.
	 */
	private void properFillDeviceCategories()
	{
		for (Class<?> devCat : ZWaveThermostaticRadiatorValveInstance.class
				.getInterfaces())
		{
			thermostaticRadiatorValveDeviceCategories.add(devCat.getName());
		}
	}

	@Override
	public void updated(Dictionary<String, ?> properties)
			throws ConfigurationException
	{
		if (properties != null)
		{
			// try to get the baseline polling time
			String updateTimeAsString = (String) properties
					.get(ZWaveInfo.PROPERTY_UPDATETIME);

			// trim leading and trailing spaces
			updateTimeAsString = updateTimeAsString.trim();

			// check not null
			if (updateTimeAsString != null)
			{
				// parse the string
				updateTimeMillis = Integer.valueOf(updateTimeAsString);
			}
			else
			{
				throw new ConfigurationException(ZWaveInfo.PROPERTY_UPDATETIME,
						ZWaveInfo.PROPERTY_UPDATETIME
								+ " not defined in configuraton file for "
								+ ZWaveThermostaticRadiatorValveDriver.class
										.getName());
			}

			// try to get the persistence store directory
			String persistenceDir = (String) properties
					.get(ZWaveInfo.PROPERTY_PERSISTENT_STORE);

			// trim leading and trailing spaces
			persistenceDir = persistenceDir.trim();

			// check not null
			if (persistenceDir != null)
			{
				// parse the string
				this.persistenceStoreDirectory = persistenceDir;
				
				//check absolute vs relative
				File persistenceStoreDir = new File (this.persistenceStoreDirectory);
				if(!persistenceStoreDir.isAbsolute())
					this.persistenceStoreDirectory = System.getProperty("configFolder") + "/" +this.persistenceStoreDirectory;
			}

			this.registerDriver();
		}
	}

	@Override
	public void handleEvent(Event event)
	{
		//check that the received event is a clock notification
		final Object eventContent = event.getProperty(EventConstants.EVENT);
		
		if(eventContent instanceof ClockTimeNotification)
		{
			//handle the clock notification asynchronously?
			Runnable clockNotificationTask = new Runnable()
			{
				
				@Override
				public void run()
				{
					// dispatch the event content
					for(ZWaveThermostaticRadiatorValveInstance driverInstance: connectedDrivers)
					{
						driverInstance.checkTime(((ClockTimeNotification)eventContent).getClockTick());
					}
					
				}
			};
			
			//create an execution thread for event handling
			Thread taskRunner = new Thread(clockNotificationTask);
			
			//start the task
			taskRunner.start();
			
		}
		
	}

}