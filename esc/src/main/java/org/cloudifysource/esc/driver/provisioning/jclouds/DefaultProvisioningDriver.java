/*******************************************************************************
 * Copyright (c) 2011 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *******************************************************************************/
package org.cloudifysource.esc.driver.provisioning.jclouds;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.DefaultHttpClient;
import org.cloudifysource.dsl.cloud.Cloud;
import org.cloudifysource.dsl.cloud.FileTransferModes;
import org.cloudifysource.dsl.cloud.compute.ComputeTemplate;
import org.cloudifysource.dsl.internal.CloudifyErrorMessages;
import org.cloudifysource.dsl.rest.response.ControllerDetails;
import org.cloudifysource.esc.driver.provisioning.BaseProvisioningDriver;
import org.cloudifysource.esc.driver.provisioning.CloudProvisioningException;
import org.cloudifysource.esc.driver.provisioning.CustomServiceDataAware;
import org.cloudifysource.esc.driver.provisioning.MachineDetails;
import org.cloudifysource.esc.driver.provisioning.ManagementLocator;
import org.cloudifysource.esc.driver.provisioning.ProvisioningDriver;
import org.cloudifysource.esc.driver.provisioning.context.ProvisioningDriverClassContextAware;
import org.cloudifysource.esc.installer.InstallerException;
import org.cloudifysource.esc.jclouds.JCloudsDeployer;
import org.jclouds.apis.ApiMetadata;
import org.jclouds.apis.Apis;
import org.jclouds.cloudstack.CloudStackAsyncClient;
import org.jclouds.cloudstack.CloudStackClient;
import org.jclouds.cloudstack.features.SSHKeyPairClient;
import org.jclouds.cloudstack.features.SecurityGroupClient;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.domain.Location;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.EC2AsyncClient;
import org.jclouds.ec2.EC2Client;
import org.jclouds.ec2.domain.KeyPair;
import org.jclouds.ec2.services.KeyPairClient;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaAsyncApi;
import org.jclouds.openstack.nova.v2_0.extensions.KeyPairApi;
import org.jclouds.openstack.nova.v2_0.extensions.SecurityGroupApi;
import org.jclouds.providers.ProviderMetadata;
import org.jclouds.providers.Providers;
import org.jclouds.rest.RestContext;

import com.google.common.base.Predicate;

/**************
 * A jclouds-based CloudifyProvisioning implementation. Uses the JClouds Compute Context API to provision an image with
 * linux installed and ssh available. If GigaSpaces is not already installed on the new machine, this class will install
 * gigaspaces and run the agent.
 *
 * @author barakme, noak
 * @since 2.0.0
 */
public class DefaultProvisioningDriver extends BaseProvisioningDriver implements
		ProvisioningDriver, ProvisioningDriverClassContextAware,
		CustomServiceDataAware, ManagementLocator {

	private static final int CLOUD_NODE_STATE_POLLING_INTERVAL = 2000;
	private static final String DEFAULT_EC2_WINDOWS_USERNAME = "Administrator";
	private static final String EC2_API = "aws-ec2";
	private static final String VCLOUD = "vcloud";
	private static final String OPENSTACK_API = "openstack-nova";
	private static final String CLOUDSTACK = "cloudstack";
	private static final String ENDPOINT_OVERRIDE = "jclouds.endpoint";
	
	private JCloudsDeployer deployer;

	@Override
	protected void initDeployer(final Cloud cloud) {
		if (this.deployer != null) {
			return;
		}

		try {
			// TODO - jcloudsUniqueId should be unique per cloud configuration.
			// TODO - The deployer object should be reusable across templates.
			// The current API is not appropriate.
			// TODO - key should be based on entire cloud configuraion!
			// TODO - this shared context only works if we have reference
			// counting, to check when this item is
			// no longer there. Otherwise, either this context will leak, or it
			// will be shutdown by the first
			// service to by undeployed.
			this.deployer = createDeployer(cloud);
			// (JCloudsDeployer)
			// context.getOrCreate("UNIQUE_JCLOUDS_DEPLOYER_ID_" +
			// this.cloudTemplateName,
			// new Callable<Object>() {
			//
			// @Override
			// public Object call()
			// throws Exception {
			// return createDeplyer(cloud);
			// }
			// });
		} catch (final Exception e) {
			publishEvent("connection_to_cloud_api_failed", cloud.getProvider()
					.getProvider());
			throw new IllegalStateException("Failed to create cloud Deployer",
					e);
		}
	}

	@Override
	public MachineDetails startMachine(final String locationId, final long timeout,
			final TimeUnit unit) throws TimeoutException,
			CloudProvisioningException {

		logger.fine(this.getClass().getName()
				+ ": startMachine, management mode: " + management);
		final long end = System.currentTimeMillis() + unit.toMillis(timeout);

		if (System.currentTimeMillis() > end) {
			throw new TimeoutException("Starting a new machine timed out");
		}

		try {
			final String groupName = createNewServerName();
			logger.fine("Starting a new cloud server with group: " + groupName);
			final MachineDetails md = createServer(end, groupName, locationId);
			return md;
		} catch (final Exception e) {
			throw new CloudProvisioningException(
					"Failed to start cloud machine", e);
		}
	}

	@Override
	protected MachineDetails createServer(final String serverName, final long endTime,
			final ComputeTemplate template) throws CloudProvisioningException,
			TimeoutException {
		return createServer(endTime, serverName, null);
	}

	private MachineDetails createServer(final long end, final String groupName,
			final String locationIdOverride) throws CloudProvisioningException {

		final ComputeTemplate cloudTemplate = this.cloud.getCloudCompute().getTemplates().get(
				this.cloudTemplateName);
		String locationId;
		if (locationIdOverride == null) {
			locationId = cloudTemplate.getLocationId();
		} else {
			locationId = locationIdOverride;
		}

		NodeMetadata node;
		final MachineDetails machineDetails;

		try {
			logger.fine("Cloudify Deployer is creating a new server with tag: "
					+ groupName + ". This may take a few minutes");
			node = deployer.createServer(groupName, locationId);
		} catch (final InstallerException e) {
			throw new CloudProvisioningException(
					"Failed to create cloud server", e);
		}
		logger.fine("New node is allocated, group name: " + groupName);

		final String nodeId = node.getId();

		// At this point the machine is starting. Any error beyond this point
		// must clean up the machine

		try {
			// wait for node to reach RUNNING state
			node = waitForNodeToBecomeReady(nodeId, end);

			// Create MachineDetails for the node metadata.
			machineDetails = createMachineDetailsFromNode(node);

			final FileTransferModes fileTransfer = cloudTemplate
					.getFileTransfer();

			if (this.cloud.getProvider().getProvider().equals("aws-ec2")
					&& fileTransfer == FileTransferModes.CIFS) {
				// Special password handling for windows on EC2
				if (machineDetails.getRemotePassword() == null) {
					// The template did not specify a password, so we must be
					// using the aws windows password mechanism.
					handleEC2WindowsCredentials(end, node, machineDetails,
							cloudTemplate);
				}

			} else {
				// Credentials required special handling.
				handleServerCredentials(machineDetails, cloudTemplate);
			}

		} catch (final Exception e) {
			// catch any exception - to prevent a cloud machine leaking.
			logger.log(
					Level.SEVERE,
					"Cloud machine was started but an error occured during initialization. Shutting down machine",
					e);
			deployer.shutdownMachine(nodeId);
			throw new CloudProvisioningException(e);
		}

		return machineDetails;
	}

	private void handleEC2WindowsCredentials(final long end,
			final NodeMetadata node, final MachineDetails machineDetails,
			final ComputeTemplate cloudTemplate) throws FileNotFoundException,
			InterruptedException, TimeoutException, CloudProvisioningException {
		File pemFile = null;

		if (this.management) {
			final File localDirectory = new File(cloudTemplate.getAbsoluteUploadDir());

			pemFile = new File(localDirectory, cloudTemplate.getKeyFile());
		} else {
			final String localDirectoryName = cloudTemplate.getLocalDirectory();
			logger.fine("local dir name is: " + localDirectoryName);
			final File localDirectory = new File(localDirectoryName);

			pemFile = new File(localDirectory, cloudTemplate.getKeyFile());
		}

		if (!pemFile.exists()) {
			logger.severe("Could not find pem file: " + pemFile);
			throw new FileNotFoundException("Could not find key file: "
					+ pemFile);
		}

		String password;
		if (cloudTemplate.getPassword() == null) {
			// get the password using Amazon API
			this.publishEvent("waiting_for_ec2_windows_password", node.getId());

			final LoginCredentials credentials = new EC2WindowsPasswordHandler()
					.getPassword(node, this.deployer.getContext(), end, pemFile);
			password = credentials.getPassword();

			this.publishEvent("ec2_windows_password_retrieved", node.getId());

		} else {
			password = cloudTemplate.getPassword();
		}

		String username = cloudTemplate.getUsername();

		if (username == null) {
			username = DEFAULT_EC2_WINDOWS_USERNAME;
		}
		machineDetails.setRemoteUsername(username);
		machineDetails.setRemotePassword(password);
		machineDetails.setFileTransferMode(cloudTemplate.getFileTransfer());
		machineDetails.setRemoteExecutionMode(cloudTemplate
				.getRemoteExecution());
	}

	private NodeMetadata waitForNodeToBecomeReady(final String id,
			final long end) throws CloudProvisioningException,
			InterruptedException, TimeoutException {
		NodeMetadata node = null;
		while (System.currentTimeMillis() < end) {
			node = deployer.getServerByID(id);

			switch (node.getStatus()) {
			case RUNNING:
				return node;
			case PENDING:
				logger.fine("Server Status (" + id
						+ ") still PENDING, please wait...");
				Thread.sleep(CLOUD_NODE_STATE_POLLING_INTERVAL);
				break;
			case TERMINATED:
			case ERROR:
			case UNRECOGNIZED:
			case SUSPENDED:
			default:
				throw new CloudProvisioningException(
						"Failed to allocate server - Cloud reported node in "
								+ node.getStatus().toString()
								+ " state. Node details: " + node);
			}

		}
		throw new TimeoutException("Node failed to reach RUNNING mode in time");
	}

	/*********
	 * Looks for a free server name by appending a counter to the pre-calculated server name prefix. If the max counter
	 * value is reached, code will loop back to 0, so that previously used server names will be reused.
	 *
	 * @return the server name.
	 * @throws CloudProvisioningException
	 *             if no free server name could be found.
	 */
	private String createNewServerName() throws CloudProvisioningException {

		String serverName = null;
		int attempts = 0;
		boolean foundFreeName = false;

		while (attempts < MAX_SERVERS_LIMIT) {
			// counter = (counter + 1) % MAX_SERVERS_LIMIT;
			++attempts;
			serverName = serverNamePrefix + counter.incrementAndGet();
			// verifying this server name is not already used
			final NodeMetadata existingNode = deployer
					.getServerByID(serverName);
			if (existingNode == null) {
				foundFreeName = true;
				break;
			}
		}

		if (!foundFreeName) {
			throw new CloudProvisioningException(
					"Number of servers has exceeded allowed server limit ("
							+ MAX_SERVERS_LIMIT + ")");
		}

		return serverName;
	}

	@Override
	public MachineDetails[] startManagementMachines(final long duration,
			final TimeUnit unit) throws TimeoutException,
			CloudProvisioningException {

		if (duration < 0) {
			throw new TimeoutException("Starting a new machine timed out");
		}
		final long endTime = System.currentTimeMillis()
				+ unit.toMillis(duration);

		logger.fine("DefaultCloudProvisioning: startMachine - management == "
				+ management);

		final String managementMachinePrefix = this.cloud.getProvider().getManagementGroup();
		if (StringUtils.isBlank(managementMachinePrefix)) {
			throw new CloudProvisioningException(
					"The management group name is missing - can't locate existing servers!");
		}

		// first check if management already exists
		final MachineDetails[] existingManagementServers = getExistingManagementServers();
		if (existingManagementServers.length > 0) {
			final String serverDescriptions =
					createExistingServersDescription(managementMachinePrefix, existingManagementServers);
			throw new CloudProvisioningException("Found existing servers matching group "
					+ managementMachinePrefix + ": " + serverDescriptions);

		}

		// launch the management machines
		publishEvent(EVENT_ATTEMPT_START_MGMT_VMS);
		final int numberOfManagementMachines = this.cloud.getProvider()
				.getNumberOfManagementMachines();
		final MachineDetails[] createdMachines = doStartManagementMachines(
				endTime, numberOfManagementMachines);
		publishEvent(EVENT_MGMT_VMS_STARTED);
		return createdMachines;
	}

	private String createExistingServersDescription(final String managementMachinePrefix,
			final MachineDetails[] existingManagementServers) {
		logger.info("Found existing servers matching the name: "
				+ managementMachinePrefix);
		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (final MachineDetails machineDetails : existingManagementServers) {
			final String existingManagementServerDescription = createManagementServerDescription(machineDetails);
			if(first) {
				first = false;
			} else {
				sb.append(", ");
			}
			sb.append("[").append(existingManagementServerDescription).append("]");
		}
		final String serverDescriptions = sb.toString();
		return serverDescriptions;
	}

	private String createManagementServerDescription(final MachineDetails machineDetails) {
		final StringBuilder sb = new StringBuilder();
		sb.append("Machine ID: ").append(machineDetails.getMachineId());
		if (machineDetails.getPublicAddress() != null) {
			sb.append(", Public IP: ").append(machineDetails.getPublicAddress());
		}

		if (machineDetails.getPrivateAddress() != null) {
			sb.append(", Private IP: ").append(machineDetails.getPrivateAddress());
		}

		return sb.toString();
	}

	@Override
	public boolean stopMachine(final String serverIp, final long duration,
			final TimeUnit unit) throws CloudProvisioningException,
			TimeoutException, InterruptedException {

		boolean stopResult = false;

		logger.info("Stop Machine - machineIp: " + serverIp);

		logger.info("Looking up cloud server with IP: " + serverIp);
		final NodeMetadata server = deployer.getServerWithIP(serverIp);
		if (server != null) {
			logger.info("Found server: "
					+ server.getId()
					+ ". Shutting it down and waiting for shutdown to complete");
			deployer.shutdownMachineAndWait(server.getId(), unit, duration);
			logger.info("Server: " + server.getId()
					+ " shutdown has finished.");
			stopResult = true;
		} else {
			logger.log(
					Level.SEVERE,
					"Recieved scale in request for machine with ip "
							+ serverIp
							+ " but this IP could not be found in the Cloud server list");
			stopResult = false;
		}

		return stopResult;
	}

	@Override
	public void stopManagementMachines() throws TimeoutException,
			CloudProvisioningException {

		initDeployer(this.cloud);
		final MachineDetails[] managementServers = getExistingManagementServers();

		if (managementServers.length == 0) {
			throw new CloudProvisioningException(
					"Could not find any management machines for this cloud (management machine prefix is: "
							+ this.serverNamePrefix + ")");
		}

		final Set<String> machineIps = new HashSet<String>();
		for (final MachineDetails machineDetails : managementServers) {
			machineIps.add(machineDetails.getPrivateAddress());
		}

		this.deployer.shutdownMachinesWithIPs(machineIps);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.cloudifysource.esc.driver.provisioning.jclouds.ManagementLocator#getExistingManagementServers()
	 */
	@Override
	public MachineDetails[] getExistingManagementServers() throws CloudProvisioningException {
		final String managementMachinePrefix = this.serverNamePrefix;
		Set<? extends NodeMetadata> existingManagementServers = null;
		try {
			existingManagementServers = this.deployer
					.getServers(new Predicate<ComputeMetadata>() {

						@Override
						public boolean apply(final ComputeMetadata input) {
							final NodeMetadata node = (NodeMetadata) input;
							if (node.getGroup() == null) {
								return false;
							}
							// only running or pending nodes are interesting
							if (node.getStatus() == NodeMetadata.Status.RUNNING
									|| node.getStatus() == NodeMetadata.Status.PENDING) {
								return node
										.getGroup()
										.toLowerCase()
										.startsWith(
												managementMachinePrefix
														.toLowerCase());
							}
							return false;
						}
					});

		} catch (final Exception e) {
			throw new CloudProvisioningException("Failed to read existing management servers: " + e.getMessage(), e);
		}

		final MachineDetails[] result = new MachineDetails[existingManagementServers
				.size()];
		int i = 0;
		for (final NodeMetadata node : existingManagementServers) {
			result[i] = createMachineDetailsFromNode(node);
//			result[i].setAgentRunning(true);
//			result[i].setCloudifyInstalled(true);
			i++;

		}
		return result;
	}

	@Override
	protected void handleProvisioningFailure(
			final int numberOfManagementMachines, final int numberOfErrors,
			final Exception firstCreationException,
			final MachineDetails[] createdManagementMachines)
			throws CloudProvisioningException {
		logger.severe("Of the required " + numberOfManagementMachines
				+ " management machines, " + numberOfErrors
				+ " failed to start.");
		if (numberOfManagementMachines > numberOfErrors) {
			logger.severe("Shutting down the other managememnt machines");

			for (final MachineDetails machineDetails : createdManagementMachines) {
				if (machineDetails != null) {
					logger.severe("Shutting down machine: " + machineDetails);
					this.deployer
							.shutdownMachine(machineDetails.getMachineId());
				}
			}
		}

		throw new CloudProvisioningException(
				"One or more managememnt machines failed. The first encountered error was: "
						+ firstCreationException.getMessage(),
				firstCreationException);
	}

	private MachineDetails createMachineDetailsFromNode(final NodeMetadata node) {
		final ComputeTemplate template = this.cloud.getCloudCompute().getTemplates().get(
				this.cloudTemplateName);

		final MachineDetails md = createMachineDetailsForTemplate(template);

		md.setCloudifyInstalled(false);
		md.setInstallationDirectory(null);
		md.setMachineId(node.getId());
		if (node.getPrivateAddresses().size() > 0) {
			md.setPrivateAddress(node.getPrivateAddresses().iterator().next());
		}
		if (node.getPublicAddresses().size() > 0) {
			md.setPublicAddress(node.getPublicAddresses().iterator().next());
		}

		final String username = createMachineUsername(node, template);
		final String password = createMachinePassword(node, template);

		md.setRemoteUsername(username);
		md.setRemotePassword(password);

		// this will ensure that the availability zone is added to GSA that
		// starts on this machine.
		final String locationId = node.getLocation().getId();
		md.setLocationId(locationId);

		return md;
	}

	private String createMachineUsername(final NodeMetadata node,
			final ComputeTemplate template) {

		// Template configuration takes precedence.
		if (template.getUsername() != null) {
			return template.getUsername();
		}

		// Check if node returned a username
		if (node.getCredentials() != null) {
			final String serverIdentity = node.getCredentials().identity;
			if (serverIdentity != null) {
				return serverIdentity;
			}
		}

		return null;
	}

	private String createMachinePassword(final NodeMetadata node,
			final ComputeTemplate template) {

		// Template configuration takes precedence.
		if (template.getPassword() != null) {
			return template.getPassword();
		}

		// Check if node returned a username - some clouds support this
		// (Rackspace, for instance)
		if (node.getCredentials() != null
				&& node.getCredentials().getOptionalPassword() != null) {
			if (node.getCredentials().getOptionalPassword().isPresent()) {
				return node.getCredentials().getPassword();
			}
		}

		return null;
	}

	@Override
	public void close() {
		if (deployer != null) {
			deployer.close();	
		}
	}

	private JCloudsDeployer createDeployer(final Cloud cloud)
			throws IOException {
		logger.fine("Creating JClouds context deployer with user: "
				+ cloud.getUser().getUser());
		final ComputeTemplate cloudTemplate = cloud.getCloudCompute().getTemplates().get(
				cloudTemplateName);

		logger.fine("Cloud Template: " + cloudTemplateName + ". Details: "
				+ cloudTemplate);
		final Properties props = new Properties();
		props.putAll(cloudTemplate.getOverrides());

		deployer = new JCloudsDeployer(cloud.getProvider().getProvider(), cloud
				.getUser().getUser(), cloud.getUser().getApiKey(), props);

		deployer.setImageId(cloudTemplate.getImageId());
		deployer.setMinRamMegabytes(cloudTemplate.getMachineMemoryMB());
		deployer.setHardwareId(cloudTemplate.getHardwareId());
		deployer.setExtraOptions(cloudTemplate.getOptions());
		return deployer;
	}

	@Override
	public void setCustomDataFile(final File customDataFile) {
		logger.info("Received custom data file: " + customDataFile);
	}

	@Override
	public MachineDetails[] getExistingManagementServers(final ControllerDetails[] controllers)
			throws CloudProvisioningException, UnsupportedOperationException {
		throw new UnsupportedOperationException(
				"Locating management servers from file information is not supported in this cloud driver");
	}

	@Override
	public Object getComputeContext() {
		ComputeServiceContext computeContext = null;
		if (deployer != null) {
			computeContext = deployer.getContext();
		}

		return computeContext;
	}
	
	@Override
	protected void validateCloudConfiguration() throws CloudProvisioningException {
		// 1. Provider/API name
		// 2. Authentication to the cloud
		// 3. Image IDs
		// 4. Hardware IDs
		// 5. Location IDs
		// 6. Cloudify download URL
		// 7. Security groups
		// 8. Key-pair names (TODO: finger-print check)
		// TODO : move the security groups to the Template section (instead of  custom map), 
		// it is now supported by jclouds.

		
		JCloudsDeployer validationDeployer = null;
		final ComputeService computeService;
		String providerName = cloud.getProvider().getProvider();
		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_PROVIDER_OR_API_NAME.getName(), providerName);
		String apiId;
		String endpoint = null;
		boolean endpointRequired = false;
		
		try {
			ProviderMetadata providerMetadata = Providers.withId(providerName);
			ApiMetadata apiMetadata = providerMetadata.getApiMetadata();
			apiId = apiMetadata.getId();
		} catch (NoSuchElementException e) {
			//there is no jclouds Provider by that name, this could be the name of an API used in a private cloud
			try {
				ApiMetadata apiMetadata = Apis.withId(providerName);
				apiId = apiMetadata.getId();
				endpointRequired = true;
				endpoint = getEndpoint();
				if (StringUtils.isBlank(endpoint)) {
					throw new CloudProvisioningException("Endpoint is missing");
				}
			} catch (NoSuchElementException ex) {
				throw new CloudProvisioningException("Provider not supported: " + providerName, ex);
			}
		}
		
		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_CLOUD_CREDENTIALS.getName());
		try {
			Properties properties = new Properties();
			if (endpointRequired) {
				// we have to assume all templates share the same endpoint
				properties.put(ENDPOINT_OVERRIDE, endpoint);
			}
			validationDeployer = new JCloudsDeployer(providerName, cloud.getUser().getUser(), 
					cloud.getUser().getApiKey(), properties);
			computeService = validationDeployer.getContext().getComputeService();
		} catch (IOException e) {
			//does this necessarily indicate the credentials are wrong? need to test.
			throw new CloudProvisioningException("Authentication to cloud failed");
		}
		
		try {
			performBasicValidations(computeService);
			validateComputeTemplates(validationDeployer);
			validateCloudifyUrl(cloud.getProvider().getCloudifyUrl());
			if (StringUtils.isNotBlank(apiId) && isKnownAPI(apiId)) {
				validateSecurityGroups(validationDeployer, apiId);
				validateKeyPairs(validationDeployer, apiId);
			}
		} finally {
			validationDeployer.close();
		}

	}
	
	private void performBasicValidations(final ComputeService computeService) throws CloudProvisioningException {
		
		Set<String> imageIds = new HashSet<String>();
		Set<String> hardwareIds = new HashSet<String>();
		Set<String> locationIds = new HashSet<String>();
		
		parseComputeTemplates(imageIds, hardwareIds, locationIds);
		
		validateImageIds(computeService, imageIds);
		validateHardwareIds(computeService.listHardwareProfiles(), hardwareIds);
		validateLocationIds(computeService.listAssignableLocations(), locationIds);
	}

	
	private void validateComputeTemplates(final JCloudsDeployer validationDeployer) throws CloudProvisioningException {
		
		JCloudsDeployer effectiveDeployer;
		String templateName = "";
		
		try {
			for (Entry<String, ComputeTemplate> entry : cloud.getCloudCompute().getTemplates().entrySet()) {
				templateName = entry.getKey();
				publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_TEMPLATE.getName(), templateName);
				ComputeTemplate template = entry.getValue();
				effectiveDeployer = validationDeployer;
				
				// if the template includes overrides *other than the endpoint* - create a new
				// deployer for this specific template with the overriding settings
				Map<String, Object> templateOverrides = template.getOverrides();
				if (templateOverrides != null && templateOverrides.size() > 0) {
					if ((templateOverrides.containsKey(ENDPOINT_OVERRIDE) && templateOverrides.size() > 1)
							|| (!templateOverrides.containsKey(ENDPOINT_OVERRIDE) && templateOverrides.size() > 0)) {
						publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_TEMPLATE_OVERRIDES.getName());
						try {
							final Properties props = new Properties();
							props.putAll(template.getOverrides());
							effectiveDeployer = new JCloudsDeployer(cloud.getProvider().getProvider(), 
									cloud.getUser().getUser(), cloud.getUser().getApiKey(), props);
						} catch (IOException e) {
							//does this necessarily indicate the credentials are wrong? need to test.
							throw new CloudProvisioningException("Authentication to cloud failed");
						}
					}
				}
					
				effectiveDeployer.setImageId(template.getImageId());
				effectiveDeployer.setHardwareId(template.getHardwareId());
				// TODO: check this memory validation
				// effectiveDeployer.setMinRamMegabytes(template.getMachineMemoryMB());
				publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_IMAGE_ID.getName(),
						template.getImageId() == null ? "" : template.getImageId());				
				publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_HARDWARE_ID.getName(),
						template.getHardwareId() == null ? "" : template.getHardwareId());
				publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_LOCATION_ID.getName(), 
						template.getLocationId() == null ? "" : template.getLocationId());
				// calling JCloudsDeployer.getTemplate effectively tests the above configuration through jclouds
				effectiveDeployer.getTemplate(template.getLocationId());
			}
		} catch (Exception e) {
			throw new CloudProvisioningException("Invalid configuration for template \"" + templateName + "\", " 
					+ e.getMessage());
		}
	}
	
	
	private void parseComputeTemplates(final Set<String> imageIds, final Set<String> hardwareIds, 
			final Set<String> locationIds) throws CloudProvisioningException {
		
		for (Entry<String, ComputeTemplate> entry : cloud.getCloudCompute().getTemplates().entrySet()) {
			String imageId = null;
			String hardwareProfileId = null;
			String locationId = null;
			
			
			String templateName = entry.getKey();
			publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_TEMPLATE.getName(), templateName);
			ComputeTemplate template = entry.getValue();
			
			// imageId
			imageId = template.getImageId();
			if (!StringUtils.isBlank(imageId)) {
				imageIds.add(imageId);
			} /*else {
				throw new CloudProvisioningException("Invalid image - image id cannot be empty");
			}*/
			
			// hardwareId
			hardwareProfileId = template.getHardwareId();
			if (!StringUtils.isBlank(hardwareProfileId)) {
				hardwareIds.add(hardwareProfileId);
			} /*else {
				throw new CloudProvisioningException("Invalid hardware profile - hardware id cannot be empty");	
			}*/
			
			// locationId
			locationId = template.getLocationId();
			if (!StringUtils.isBlank(locationId)) {
				locationIds.add(template.getLocationId());
			} /*else {
				throw new CloudProvisioningException("Invalid location - location id cannot be empty");
			}*/
		}
	}
	
	
	private Map<String, Set<String>> getSecurityGroupsByRegions(final String defaultLocationId) {
		
		Map<String, Set<String>> securityGroupsByRegions = new HashMap<String, Set<String>>();
		
		for (ComputeTemplate template : cloud.getCloudCompute().getTemplates().values()) {
			String locationId = template.getLocationId();
			if (StringUtils.isBlank(locationId)) {
				// TODO : use the default location id for that template
				locationId = defaultLocationId;
			}
			
			Object securityGroupsArr = template.getOptions().get("securityGroupNames");
			if (securityGroupsArr == null) {
				securityGroupsArr = template.getOptions().get("securityGroups");
			}
			if (securityGroupsArr != null && securityGroupsArr instanceof String[]) {
				Set<String> securityGroupNames = new HashSet<String>();
				for (String securityGroupName : (String[]) securityGroupsArr) {
					securityGroupNames.add(securityGroupName);
				}
				
				Set<String> groupsForRegion = securityGroupsByRegions.get(locationId);
				if (groupsForRegion == null) {
					securityGroupsByRegions.put(locationId, securityGroupNames);
				} else {
					groupsForRegion.addAll(securityGroupNames);
					securityGroupsByRegions.put(locationId, groupsForRegion);
				}
			}
		}
		
		return securityGroupsByRegions;
	}
	
	
	private Map<String, Set<String>> getKeyPairsByRegions(final String defaultLocationId) {
		
		Map<String, Set<String>> keyPairsByRegions = new HashMap<String, Set<String>>();
		
		for (ComputeTemplate template : cloud.getCloudCompute().getTemplates().values()) {
			String locationId = template.getLocationId();
			if (StringUtils.isBlank(locationId)) {
				// TODO : use the default location id for that template
				locationId = defaultLocationId;
			}

			String keyPair = (String) template.getOptions().get("keyPairName");
			if (keyPair == null) {
				keyPair = (String) template.getOptions().get("keyPair");
			}
			if (StringUtils.isNotBlank(keyPair)) {
				Set<String> keyPairsForRegion = keyPairsByRegions.get(locationId);
				if (keyPairsForRegion == null) {
					Set<String> keyPairNames = new HashSet<String>();
					keyPairNames.add(keyPair);
					keyPairsByRegions.put(locationId, keyPairNames);
				} else {
					keyPairsForRegion.add(keyPair);
					keyPairsByRegions.put(locationId, keyPairsForRegion);
				}
			}
		}
		
		return keyPairsByRegions;
	}
	
	private void validateImageIds(final ComputeService computeService, final Set<String> usedImageIds) 
			throws CloudProvisioningException {
		
		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_IMAGES.getName());
		Set<String> missingImageIds = new HashSet<String>();
		
		for (String imageId : usedImageIds) {
			try {
				computeService.getImage(imageId);
			} catch (IllegalArgumentException e) {
				missingImageIds.add(imageId);
			}
		}
		
		if (!missingImageIds.isEmpty()) {
			if (missingImageIds.size() == 1) {
				throw new CloudProvisioningException("Invalid image ID: " + missingImageIds.iterator().next());
			} else {
				throw new CloudProvisioningException("Invalid image IDs: "
					+ Arrays.toString(missingImageIds.toArray()));
		}
	}
	}
	
	private void validateHardwareIds(final Set<? extends Hardware> supportedHardwareProfiles, 
			final Set<String> usedHardwareIds) throws CloudProvisioningException {
		
		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_HARDWARE_PROFILES.getName());
		Set<String> supportedHardwareIds = new HashSet<String>();
		for (Hardware hardware : supportedHardwareProfiles) {
			supportedHardwareIds.add(hardware.getId());	
		}
		
		Set<String> missingHardwareIds = new HashSet<String>(usedHardwareIds);
		missingHardwareIds.removeAll(supportedHardwareIds);
		if (!missingHardwareIds.isEmpty()) {
			if (missingHardwareIds.size() == 1) {
				throw new CloudProvisioningException("Invalid hardware ID: " + missingHardwareIds.iterator().next());
			} else {
				throw new CloudProvisioningException("Invalid hardware IDs: "
					+ Arrays.toString(missingHardwareIds.toArray()));
		}
	}
	}
	
	private void validateLocationIds(final Set<? extends Location> supportedLocations, 
			final Set<String> usedLocationIds) throws CloudProvisioningException {
		
		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_LOCATIONS.getName());
		Set<String> supportedLocationIds = new HashSet<String>();
		for (Location location : supportedLocations) {
			supportedLocationIds.add(location.getId());	
		}
		
		Set<String> missingLocationIds = new HashSet<String>(usedLocationIds);
		missingLocationIds.removeAll(supportedLocationIds);
		if (!missingLocationIds.isEmpty()) {
			if (missingLocationIds.size() == 1) {
				throw new CloudProvisioningException("Invalid location ID: " + missingLocationIds.iterator().next());
			} else {
				throw new CloudProvisioningException("Invalid location IDs: " 
					+ Arrays.toString(missingLocationIds.toArray()));
			}
		}
	}

	
	private void validateSecurityGroups(final JCloudsDeployer validationDeployer, final String apiId) 
			throws CloudProvisioningException {
		
		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_SECURITY_GROUPS.getName());
		ComputeServiceContext computeServiceContext = validationDeployer.getContext();
		Map<String, Set<String>> securityGroupsByRegions = 
				getSecurityGroupsByRegions(getDefaultLocation(validationDeployer));
		
		if (apiId.equalsIgnoreCase(EC2_API)) {
			RestContext<EC2Client, EC2AsyncClient> unwrapped = computeServiceContext.unwrap();
			validateEc2SecurityGroups(unwrapped.getApi().getSecurityGroupServices(), securityGroupsByRegions);

		} else if (apiId.equalsIgnoreCase(OPENSTACK_API)) {
			RestContext<NovaApi, NovaAsyncApi> unwrapped = computeServiceContext.unwrap();
			validateOpenstackSecurityGroups(unwrapped.getApi(), securityGroupsByRegions);
			
		} else if (apiId.equalsIgnoreCase(CLOUDSTACK)) {
			RestContext<CloudStackClient, CloudStackAsyncClient> unwrapped = computeServiceContext.unwrap();
			validateCloudstackSecurityGroups(unwrapped.getApi().getSecurityGroupClient(), 
					aggregateAllValues(securityGroupsByRegions));
			
		} else if (apiId.equalsIgnoreCase(VCLOUD)) {
			//security groups not supported			
		} else {
			// api validations not supported yet
		}
	}
	
	
	private void validateKeyPairs(final JCloudsDeployer validationDeployer, final String apiId)
			throws CloudProvisioningException {
		
		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_KEY_PAIRS.getName());
		ComputeServiceContext computeServiceContext = validationDeployer.getContext();
		Map<String, Set<String>> keyPairsByRegions = getKeyPairsByRegions(getDefaultLocation(validationDeployer));
		
		if (apiId.equalsIgnoreCase(EC2_API)) {
			RestContext<EC2Client, EC2AsyncClient> unwrapped = computeServiceContext.unwrap();
			validateEc2KeyPairs(unwrapped.getApi().getKeyPairServices(), keyPairsByRegions);

		} else if (apiId.equalsIgnoreCase(OPENSTACK_API)) {
			RestContext<NovaApi, NovaAsyncApi> unwrapped = computeServiceContext.unwrap();
			validateOpenstackKeyPairs(unwrapped.getApi(), keyPairsByRegions);
			
		} else if (apiId.equalsIgnoreCase(CLOUDSTACK)) {
			RestContext<CloudStackClient, CloudStackAsyncClient> unwrapped = computeServiceContext.unwrap();
			validateCloudstackKeyPairs(unwrapped.getApi().getSSHKeyPairClient(), 
					aggregateAllValues(keyPairsByRegions));
			
		} else if (apiId.equalsIgnoreCase(VCLOUD)) {
			//security groups not supported			
		} else {
			// api validations not supported yet
		}
	}
	
	private boolean isKnownAPI(final String apiName) {
		boolean supported = false;
		
		if (apiName.equalsIgnoreCase(EC2_API)
				|| apiName.equalsIgnoreCase(OPENSTACK_API)) {
//				|| apiName.equalsIgnoreCase(VCLOUD)
//				|| apiName.equalsIgnoreCase(CLOUDSTACK)) {
			supported = true;
		}
		
		return supported;
	}

	
	private void validateEc2SecurityGroups(final org.jclouds.ec2.services.SecurityGroupClient ec2SecurityGroupsClient, 
			final Map<String, Set<String>> securityGroupsByRegions) throws CloudProvisioningException {
		
		Set<String> missingSecurityGroups = new HashSet<String>();
		//org.jclouds.ec2.services.SecurityGroupClient ec2SecurityGroupsClient = client.getSecurityGroupServices();
		for (Entry<String, Set<String>> mapEntry : securityGroupsByRegions.entrySet()) {
			String region = mapEntry.getKey();
			for (String securityGroupName : mapEntry.getValue()) {
				Set<org.jclouds.ec2.domain.SecurityGroup> foundGroups = 
						ec2SecurityGroupsClient.describeSecurityGroupsInRegion(region, securityGroupName);
				if (foundGroups == null || foundGroups.size() == 0 || foundGroups.iterator().next() == null) {
					missingSecurityGroups.add(securityGroupName);
				}
			}
		}
		
		if (missingSecurityGroups.size() == 1) {
			throw new CloudProvisioningException("Invalid security group name: " 
					+ missingSecurityGroups.iterator().next());
		} else if (missingSecurityGroups.size() > 1) {
			throw new CloudProvisioningException("Invalid security group names: " 
					+ Arrays.toString(missingSecurityGroups.toArray()));
		}		
	}
	
	private void validateEc2KeyPairs(final KeyPairClient ec2KeyPairClient, 
			final Map<String, Set<String>> keyPairsByRegions) throws CloudProvisioningException {
		
		Set<String> missingKeyPairs = new HashSet<String>();
		for (Entry<String, Set<String>> mapEntry : keyPairsByRegions.entrySet()) {
			String region = mapEntry.getKey();
			for (String keyPairName : mapEntry.getValue()) {
				Set<KeyPair> foundKeyPairs = ec2KeyPairClient.describeKeyPairsInRegion(region, keyPairName);
				if (foundKeyPairs == null || foundKeyPairs.size() == 0 || foundKeyPairs.iterator().next() == null) {
					missingKeyPairs.add(keyPairName);
				}
			}
		}
		
		if (missingKeyPairs.size() == 1) {
			throw new CloudProvisioningException("Invalid key-pair name: " 
					+ missingKeyPairs.iterator().next());
		} else if (missingKeyPairs.size() > 1) {
			throw new CloudProvisioningException("Invalid key-pair names: " 
					+ Arrays.toString(missingKeyPairs.toArray()));
		}		
	}
	
	private void validateOpenstackSecurityGroups(final NovaApi novaApi, 
			final Map<String, Set<String>> securityGroupsByRegions) throws CloudProvisioningException {

		Set<String> missingSecurityGroups = new HashSet<String>();
		
		for (Entry<String, Set<String>> mapEntry : securityGroupsByRegions.entrySet()) {
			String region = mapEntry.getKey();
			SecurityGroupApi securityGroupApi = novaApi.getSecurityGroupExtensionForZone(region).get();
			
			for (String securityGroupName : mapEntry.getValue()) {
				Predicate<org.jclouds.openstack.nova.v2_0.domain.SecurityGroup> securityGroupNamePredicate = 
					org.jclouds.openstack.nova.v2_0.predicates.SecurityGroupPredicates.nameEquals(securityGroupName);
				if (!securityGroupApi.list().anyMatch(securityGroupNamePredicate)) {
					missingSecurityGroups.add(securityGroupName);
				}
			}
		}
		
		if (missingSecurityGroups.size() == 1) {
			throw new CloudProvisioningException("Invalid security group name: " 
					+ missingSecurityGroups.iterator().next());
		} else if (missingSecurityGroups.size() > 1) {
			throw new CloudProvisioningException("Invalid security group names: " 
					+ Arrays.toString(missingSecurityGroups.toArray()));
		}
		
	}
	
	private void validateOpenstackKeyPairs(final NovaApi novaApi, final Map<String, Set<String>> keyPairsByRegions)
			throws CloudProvisioningException {

		Set<String> missingKeyPairs = new HashSet<String>();
		
		for (Entry<String, Set<String>> mapEntry : keyPairsByRegions.entrySet()) {
			String region = mapEntry.getKey();
			KeyPairApi keyPairApi = novaApi.getKeyPairExtensionForZone(region).get();
			
			for (String keyPairName : mapEntry.getValue()) {
				Predicate<org.jclouds.openstack.nova.v2_0.domain.KeyPair> keyPairNamePredicate = 
						org.jclouds.openstack.nova.v2_0.predicates.KeyPairPredicates.nameEquals(keyPairName);
				if (!keyPairApi.list().anyMatch(keyPairNamePredicate)) {
					missingKeyPairs.add(keyPairName);
				}
			}
		}
		
		if (missingKeyPairs.size() == 1) {
			throw new CloudProvisioningException("Invalid key-pair name: " 
					+ missingKeyPairs.iterator().next());
		} else if (missingKeyPairs.size() > 1) {
			throw new CloudProvisioningException("Invalid key-pair names: " 
					+ Arrays.toString(missingKeyPairs.toArray()));
		}	
		
	}
	
	
	private void validateCloudstackSecurityGroups(final SecurityGroupClient securityGroupClient, 
			final Set<String> securityGroups) throws CloudProvisioningException {
		
		Set<String> missingSecurityGroups = new HashSet<String>();
		
		for (String securityGroupName : securityGroups) {
			if (securityGroupClient.getSecurityGroup(securityGroupName) == null) {
				missingSecurityGroups.add(securityGroupName);
			}
		}
		
		if (missingSecurityGroups.size() == 1) {
			throw new CloudProvisioningException("Invalid security group name: " 
					+ missingSecurityGroups.iterator().next());
		} else if (missingSecurityGroups.size() > 1) {
			throw new CloudProvisioningException("Invalid security group names: " 
					+ Arrays.toString(missingSecurityGroups.toArray()));
		}
	}
	
	private void validateCloudstackKeyPairs(final SSHKeyPairClient keyPairClient, 
			final Set<String> keyPairs) throws CloudProvisioningException {
		
		Set<String> missingKeyPairs = new HashSet<String>();
		
		for (String keyPairName : keyPairs) {
			if (keyPairClient.getSSHKeyPair(keyPairName) == null) {
				missingKeyPairs.add(keyPairName);
			}
		}
		
		if (missingKeyPairs.size() == 1) {
			throw new CloudProvisioningException("Invalid key-pair name: " 
					+ missingKeyPairs.iterator().next());
		} else if (missingKeyPairs.size() > 1) {
			throw new CloudProvisioningException("Invalid key-pair names: " 
					+ Arrays.toString(missingKeyPairs.toArray()));
		}
	}
	
	private void validateCloudifyUrl(final String cloudifyUrl) throws CloudProvisioningException {

		publishEvent(CloudifyErrorMessages.EVENT_VALIDATING_CLOUDIFY_URL.getName());
		DefaultHttpClient httpClient = new DefaultHttpClient();
		String effectiveUrl = cloudifyUrl;
		
		if (!effectiveUrl.endsWith(".zip") && !effectiveUrl.endsWith(".tar.gz")) {
			effectiveUrl += ".zip";
		}
		
		HttpHead httpMethod = new HttpHead(effectiveUrl);
		try {
			HttpResponse response = httpClient.execute(httpMethod);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				throw new CloudProvisioningException("Invalid cloudify URL: " + effectiveUrl);
			}
		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	
	private Set<String> aggregateAllValues(final Map<String, Set<String>> securityGroupsByRegions) {
		
		Set<String> securityGroupsSet = new HashSet<String>();
		for (Entry<String, Set<String>> entry: securityGroupsByRegions.entrySet()) {
			securityGroupsSet.addAll(entry.getValue());
		}
		
		return securityGroupsSet;
	}
	
	private String getEndpoint() {
		String endpoint = null;
		
		ComputeTemplate template = cloud.getCloudCompute().getTemplates().get(cloudTemplateName);
		Map<String, Object> templateOverrides = template.getOverrides();
		if (templateOverrides != null && templateOverrides.size() > 0) {
			endpoint = (String) templateOverrides.get(ENDPOINT_OVERRIDE);
		}
		
		return endpoint;
	}
	
	private String getDefaultLocation(final JCloudsDeployer validationDeployer) {
		String defaultLocationId = "";
		
		try {
			defaultLocationId = validationDeployer.getTemplate(null).getLocation().getId();
		} catch (Exception e) {
			//default location id is not always supported, it depends on the provider, no need to fail here.
			logger.fine("Validating security groups, default location id is not found for this provider");
		}
		
		return defaultLocationId;
	}
	
}
