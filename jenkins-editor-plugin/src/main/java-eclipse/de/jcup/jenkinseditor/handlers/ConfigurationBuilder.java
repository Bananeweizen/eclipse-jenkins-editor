package de.jcup.jenkinseditor.handlers;

import static de.jcup.jenkinseditor.JenkinsEditorConstants.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import de.jcup.jenkins.cli.JenkinsCLIConfiguration;
import de.jcup.jenkins.cli.JenkinsDefaultURLProvider;
import de.jcup.jenkins.cli.JenkinsCLIConfiguration.AuthMode;
import de.jcup.jenkins.util.SystemPropertyListBuilder;
import de.jcup.jenkinseditor.JenkinsEditorActivator;
import de.jcup.jenkinseditor.JenkinsEditorLogSupport;
import de.jcup.jenkinseditor.JenkinsEditorMessageDialogSupport;
import de.jcup.jenkinseditor.preferences.JenkinsEditorPreferences;

public class ConfigurationBuilder {

	public JenkinsCLIConfiguration createConfiguration(JenkinsDefaultURLProvider jenkinsDefaultURLprovider)
			throws IOException {
		JenkinsCLIConfiguration configuration = new JenkinsCLIConfiguration();

		JenkinsEditorPreferences editorPreferences = JenkinsEditorPreferences.getInstance();

		String linterJenkinsURL = editorPreferences.getJenkinsURL();
		String pathToJenkinsCLIJar = editorPreferences.getPathToJenkinsCLIJar();
		if (pathToJenkinsCLIJar == null || pathToJenkinsCLIJar.trim().length() == 0) {
			/* fall back to embedded variant */
			pathToJenkinsCLIJar = createPathToEmbeddedCLIJar();
		}
		boolean certificateCheckDisabled = editorPreferences.isCertficateCheckDisabled();
		boolean useEclipseProxySettings = editorPreferences.isUseExclipseProxyEnabled();

		ISecurePreferences preferences = SecurePreferencesFactory.getDefault();

		if (preferences.nodeExists(ID_SECURED_CREDENTIALS)) {
			ISecurePreferences node = preferences.node(ID_SECURED_CREDENTIALS);
			try {
				String user = node.get(ID_SECURED_USER_KEY, "anonymous");
				String apiToken = node.get(ID_SECURED_API_KEY, "");

				configuration.setUser(user);
				configuration.setAPIToken(apiToken);

			} catch (StorageException e1) {
				JenkinsEditorMessageDialogSupport.INSTANCE.showError("No access to secured user credentials!");
				JenkinsEditorLogSupport.INSTANCE.logError("Was not able to fetch secured credentials", e1);
				return null;
			}
		}

		if (linterJenkinsURL == null || linterJenkinsURL.trim().length() == 0) {
			linterJenkinsURL = jenkinsDefaultURLprovider.getDefaultJenkinsURL();
		}
		configuration.setCertificateCheckDisabled(certificateCheckDisabled);
		configuration.setJenkinsURL(linterJenkinsURL);
		configuration.setAuthMode(AuthMode.API_TOKEN);// currently we support
														// only
														// API KEY- in future
														// maybe
														// more/ changeable in
														// preferences
		configuration.setPathToJenkinsCLIJar(pathToJenkinsCLIJar);

		configuration.setTimeoutInSeconds(10);
		if (useEclipseProxySettings) {
			addEclipseProxySettingsToConfiguration(configuration);
		}
		return configuration;
	}

	private void addEclipseProxySettingsToConfiguration(JenkinsCLIConfiguration configuration) throws IOException {
		URI uri;
		try {
			uri = new URI(configuration.getJenkinsURL());
		} catch (URISyntaxException e) {
			throw new IOException("JENKINS URI not correct", e);
		}
		/*
		 * TODO Albert, 11.03.2018: When jenkins CLi supports normal System
		 * proxy settings we should use the next two out commented lines:
		 */
		Set<String> systemProperties = buildProxySetupWhenJenkinsCouldUseStandardSettings(uri);
		configuration.setProxySystemProperties(systemProperties);
		// configuration.setProxyParameter(buildSimpleCLIProxyParameter(uri));
	}

	private SystemPropertyListBuilder builder;

	public ConfigurationBuilder() {
		builder = new SystemPropertyListBuilder();
	}

	protected String buildSimpleCLIProxyParameter(URI uri) throws IOException {
		IProxyService proxyService = JenkinsEditorActivator.getDefault().getProxyService();
		IProxyData[] proxyDataForHost = proxyService.select(uri);

		List<String> propertyList = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		String protocol;
		try {
			protocol = uri.toURL().getProtocol();
		} catch (MalformedURLException e) {
			throw new IOException("URL malformed", e);
		}
		for (IProxyData data : proxyDataForHost) {
			if (protocol == null) {
				break;
			}
			String host = data.getHost();
			if (host != null) {
				String type = data.getType();
				if (type == null) {
					continue;
				}
				if (!protocol.equals(type.toLowerCase())) {
					continue;
				}

				propertyList.add("-p");
				sb.append(data.getHost());
				sb.append(':');
				sb.append(data.getPort());
				propertyList.add(sb.toString());

				if (data.isRequiresAuthentication()) {
					/* @formatter:off */
					throw new IOException(
							"Your proxy settings in eclipse are containing a password.\n"
							+ "Sorrowly jenkins CLI does not support passwords for proxy authentication.\n\n"
							+ "So Jenkins Editor cannot use those settings.\n"
							+ " You will have to use a local proxy without need to authenticate\n"
							+ "(e.g. use CNTLM). Sorry about the inconvenience but this is Jenkins CLi behaviour.");
					/* @formatter:on */
				}

				break;
			}
		}
		// Close the service and close the service tracker
		proxyService = null;
		return sb.toString();
	}

	/**
	 * Would work if jenkins CLI.java (and CLiConnectionFactroy.java etc.) would
	 * use standard http setup arguments from JVM. Maybe in Future... so keep
	 * this Method.
	 * 
	 * @param uri
	 * @return
	 */
	protected Set<String> buildProxySetupWhenJenkinsCouldUseStandardSettings(URI uri) {
		IProxyService proxyService = JenkinsEditorActivator.getDefault().getProxyService();
		IProxyData[] proxyDataForHost = proxyService.select(uri);
		Set<String> propertyList = new LinkedHashSet<>();

		for (IProxyData data : proxyDataForHost) {
			String host = data.getHost();
			if (host == null) {
				continue;
			}
			String type = data.getType();

			if (type == null) {
				continue;
			}
			type = type.toLowerCase(); // ensure https is lowercased
										// etc.

			int port = data.getPort();

			propertyList.add(builder.build(type, "proxyHost", host));
			propertyList.add(builder.build(type, "proxySet", "true"));
			propertyList.add(builder.build(type, "proxyPort", String.valueOf(port)));

			if (data.isRequiresAuthentication()) {
				String userid = data.getUserId();
				String pwd = data.getPassword();
				propertyList.add(builder.build(type, "proxyUser", userid));
				propertyList.add(builder.build(type, "proxyPassword", pwd));
			}
			
			propertyList.add("-Djava.net.useSystemProxies=true");
			String nonProxiedHostsProperty = createNonProxiedHostsProperty(type, proxyService);
			propertyList.add(nonProxiedHostsProperty);

		}

		// Close the service and close the service tracker
		proxyService = null;
		return propertyList;
	}

	private String createNonProxiedHostsProperty(String type, IProxyService proxyService) {
		// Dhttp.nonProxyHosts="localhost|127.0.0.1|10.*.*.*|*.foo.com‌​|etc"

		StringBuilder npSb = new StringBuilder();
		npSb.append("-D").append(type).append(".nonProxyHosts=");
		npSb.append('"');
		List<String> npList = Arrays.asList(proxyService.getNonProxiedHosts());
		Iterator<String> npIt = npList.iterator();
		while (npIt.hasNext()) {
			String nonProxiedHost = npIt.next();
			npSb.append(nonProxiedHost);
			if (npIt.hasNext()) {
				npSb.append("|");
			}
		}
		npSb.append('"');
		String nonProxiedHostsProperty = npSb.toString();
		return nonProxiedHostsProperty;
	}

	private static String createPathToEmbeddedCLIJar() throws IOException {
		File file = JenkinsEditorActivator.getDefault().getEmbeddedJenkinsCLIJarFile();
		return file.getAbsolutePath();
	}
}
