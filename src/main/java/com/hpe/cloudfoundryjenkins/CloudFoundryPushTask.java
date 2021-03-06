/**
 * © Copyright 2015 Hewlett Packard Enterprise Development LP
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 * © 2017 The original author or authors.
 */
package com.hpe.cloudfoundryjenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.Secret;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.doppler.DopplerClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationManifestUtils;
import org.cloudfoundry.operations.applications.LogsRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.services.CreateServiceInstanceRequest;
import org.cloudfoundry.operations.services.DeleteServiceInstanceRequest;
import org.cloudfoundry.operations.services.ServiceInstanceSummary;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.doppler.ReactorDopplerClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.cloudfoundry.reactor.uaa.ReactorUaaClient;
import org.cloudfoundry.uaa.UaaClient;
import reactor.core.publisher.Flux;

/**
 * Implements common push logic.
 *
 * @author williamg
 * @author Steven Swor
 */
public class CloudFoundryPushTask {

  private final String target;
  private final String organization;
  private final String cloudSpace;
  private final String credentialsId;
  private final boolean selfSigned;
  private final int pluginTimeout;
  private final List<CloudFoundryPushPublisher.Service> servicesToCreate;
  private final CloudFoundryPushPublisher.ManifestChoice manifestChoice;

  public CloudFoundryPushTask(String target, String organization, String cloudSpace, String credentialsId, boolean selfSigned, int pluginTimeout, List<CloudFoundryPushPublisher.Service> servicesToCreate, CloudFoundryPushPublisher.ManifestChoice manifestChoice) {
    this.target = target;
    this.organization = organization;
    this.cloudSpace = cloudSpace;
    this.credentialsId = credentialsId;
    this.selfSigned = selfSigned;
    this.pluginTimeout = pluginTimeout;
    this.servicesToCreate = servicesToCreate;
    this.manifestChoice = manifestChoice;
  }

  public boolean perform(FilePath workspace, Item project, Launcher launcher, TaskListener listener) {
        if (workspace == null) {
          throw new IllegalStateException("Workspace cannot be null");
        }

        listener.getLogger().println("Cloud Foundry Plugin:");

        try {
            List<StandardUsernamePasswordCredentials> standardCredentials = CredentialsProvider.lookupCredentials(
                    StandardUsernamePasswordCredentials.class,
                    project,
                    ACL.SYSTEM,
                    URIRequirementBuilder.fromUri(target).build());

            StandardUsernamePasswordCredentials credentials =
                    CredentialsMatchers.firstOrNull(standardCredentials, CredentialsMatchers.withId(credentialsId));

            if (credentials == null) {
                listener.getLogger().println("ERROR: No credentials have been given.");
                return false;
            }

            ConnectionContext connectionContext = createConnectionContext();

            TokenProvider tokenProvider = PasswordGrantTokenProvider.builder()
                .username(credentials.getUsername())
                .password(Secret.toString(credentials.getPassword()))
                .build();

            CloudFoundryClient client = ReactorCloudFoundryClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();

            DopplerClient dopplerClient = ReactorDopplerClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();

            UaaClient uaaClient = ReactorUaaClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();

            CloudFoundryOperations cloudFoundryOperations = DefaultCloudFoundryOperations.builder()
                .cloudFoundryClient(client)
                .dopplerClient(dopplerClient)
                .uaaClient(uaaClient)
                .organization(organization)
                .space(cloudSpace)
                .build();

            // Create services before push
            Flux<ServiceInstanceSummary> currentServicesList = cloudFoundryOperations.services().listInstances();
            List<String> currentServicesNames = currentServicesList.map(service -> service.getName()).collectList().block();

            for (CloudFoundryPushPublisher.Service service : servicesToCreate) {
                boolean createService = true;
                if (currentServicesNames.contains(service.name)) {
                    if (service.resetService) {
                        listener.getLogger().println("Service " + service.name + " already exists, resetting.");
                        cloudFoundryOperations.services().deleteInstance(DeleteServiceInstanceRequest.builder().name(service.name).build()).block();
                        listener.getLogger().println("Service deleted.");
                    } else {
                        createService = false;
                        listener.getLogger().println("Service " + service.name + " already exists, skipping creation.");
                    }
                }
                if (createService) {
                    listener.getLogger().println("Creating service " + service.name);
                    cloudFoundryOperations.services().createInstance(CreateServiceInstanceRequest.builder()
                        .serviceName(service.type)
                        .serviceInstanceName(service.name)
                        .planName(service.plan)
                        .build()).block();
                }
            }

            FilePath masterPath = pathOnMaster(workspace);
            boolean isOnSlave = false;
            if (masterPath == null || !masterPath.equals(workspace)) {
              masterPath = transferArtifactsToMaster(masterPath, workspace, manifestChoice, listener);
              isOnSlave = true;
            }

            List<ApplicationManifest> manifests = toManifests(masterPath, manifestChoice, isOnSlave);
            for(final ApplicationManifest manifest : manifests) {
              cloudFoundryOperations.applications().pushManifest(PushApplicationManifestRequest.builder().manifest(manifest).build())
                  .timeout(Duration.ofSeconds(pluginTimeout))
                  .doOnError(e -> e.printStackTrace(listener.getLogger()))
                  .block();
              printStagingLogs(cloudFoundryOperations, listener, manifest.getName());
            }
            if (!masterPath.equals(workspace)) {
              masterPath.deleteRecursive();
            }
            return true;
        } catch (MalformedURLException e) {
            listener.getLogger().println("ERROR: The target URL is not valid: " + e.getMessage());
            return false;
        } catch (IOException e) {
            listener.getLogger().println("ERROR: IOException: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            listener.getLogger().println("ERROR: InterruptedException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            e.printStackTrace(listener.getLogger());
            return false;
        }
  }

  private List<ApplicationManifest> toManifests(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice
          manifestChoice, boolean isOnSlave) throws IOException, InterruptedException {
      switch(manifestChoice.value) {
        case "manifestFile":
          return manifestFile(filesPath, manifestChoice);
        case "jenkinsConfig":
          return jenkinsConfig(filesPath, manifestChoice, isOnSlave);
        default:
          throw new IllegalArgumentException("manifest choice must be either 'manifestFile' or 'jenkinsConfig', but was " + manifestChoice.value);
      }
    }

    private List<ApplicationManifest> manifestFile(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice manifestChoice) throws IOException, InterruptedException {
      return ApplicationManifestUtils.read(Paths.get(new FilePath(filesPath, manifestChoice.manifestFile).toURI()))
          .stream()
          .map(manifest -> fixManifest(filesPath, manifest))
          .collect(Collectors.toList());
    }

    /**
     * Workarounds for any manifest issues should be added here.
     * @param build the build
     * @param manifest the manifest
     * @return either the original manifest or a fixed-up version of the manifest
     */
    private ApplicationManifest fixManifest(final FilePath filesPath, final ApplicationManifest manifest) {
      if (manifest.getPath()==null && (manifest.getDocker() == null || StringUtils.isEmpty(manifest.getDocker().getImage()))) {
        try {
          return ApplicationManifest.builder().from(manifest).path(Paths.get(filesPath.toURI())).build();
        } catch(IOException | InterruptedException e) {
          throw new RuntimeException(e);
        }
      } else {
        return manifest;
      }
    }

    private FilePath pathOnMaster(final FilePath path) throws IOException, InterruptedException {
      if (path.getChannel() != FilePath.localChannel) {
        // The build is distributed
        // We need to make a copy of the target file/directory on the master
        File tempFile = Files.createTempDirectory("appDir").toFile();
        tempFile.deleteOnExit();
        return new FilePath(tempFile);
      }else {
        return path;
      }
    }

    private FilePath transferArtifactsToMaster(FilePath masterPath, FilePath workspacePath, CloudFoundryPushPublisher.ManifestChoice manifestChoice, TaskListener listener) throws IOException, InterruptedException {
      FilePath results = masterPath;
      if (masterPath !=null && !masterPath.equals(workspacePath)) {
        listener.getLogger().println("INFO: Looks like we are on a distributed system... Transferring build artifacts from the slave to the master.");
        // only transfer artifacts if we aren't on the master
        FilePath appPath = new FilePath(workspacePath, manifestChoice.appPath == null ? "" : manifestChoice.appPath);
        // The build is distributed, and a directory
        // We need to make a copy of the target directory on the master
        FilePath zipFilePath = new FilePath(masterPath, "appFile");
        try(OutputStream outputStream = new FileOutputStream(Paths.get(zipFilePath.toURI()).toFile())) {
          listener.getLogger().println(String.format("INFO: Transferring from %s to %s", appPath.getRemote(), masterPath.getRemote()));
          appPath.zip(outputStream);
        }
        zipFilePath.unzip(masterPath);
        try {
          zipFilePath.delete();
        } catch(IOException ex) {
          listener.getLogger().println("WARNING: temporary files were not deleted successfully.");
        }

        // appPath.zip() creates a top level directory that we want to remove
        File[] listFiles = new File(masterPath.toURI()).listFiles();
        if (listFiles != null && listFiles.length == 1) {
            results = new FilePath(listFiles[0]);
        } else {
            // This should never happen because appPath.zip() always makes a directory
            throw new IllegalStateException("Unzipped output directory was empty.");
        }
      }
      return results;
    }

    private static List<ApplicationManifest> jenkinsConfig(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice manifestChoice) throws IOException, InterruptedException {
      ApplicationManifest.Builder manifestBuilder = ApplicationManifest.builder();
      manifestBuilder = !StringUtils.isBlank(manifestChoice.appName) ? manifestBuilder.name(manifestChoice.appName) : manifestBuilder;
      if(isOnSlave)
       manifestBuilder = !StringUtils.isBlank(manifestChoice.appPath) ? manifestBuilder.path(Paths.get(Paths.get
               (filesPath.toURI()).toString())) : manifestBuilder.path(Paths.get(filesPath.toURI()));
      else
       manifestBuilder = !StringUtils.isBlank(manifestChoice.appPath) ? manifestBuilder.path(Paths.get(Paths.get
               (filesPath.toURI()).toString(), manifestChoice.appPath)) : manifestBuilder.path(Paths.get(filesPath.toURI()));
      manifestBuilder = !StringUtils.isBlank(manifestChoice.buildpack) ? manifestBuilder.buildpack(manifestChoice.buildpack) : manifestBuilder;
      manifestBuilder = !StringUtils.isBlank(manifestChoice.command) ? manifestBuilder.command(manifestChoice.command) : manifestBuilder;
      manifestBuilder = !StringUtils.isBlank(manifestChoice.domain) ? manifestBuilder.domain(manifestChoice.domain) : manifestBuilder;
      manifestBuilder = !CollectionUtils.isEmpty(manifestChoice.envVars) ? manifestBuilder.environmentVariables(manifestChoice.envVars.stream().collect(Collectors.toMap(envVar -> envVar.key, envVar -> envVar.value))) : manifestBuilder;
      manifestBuilder = !StringUtils.isBlank(manifestChoice.hostname) ? manifestBuilder.host(manifestChoice.hostname) : manifestBuilder;
      manifestBuilder = manifestChoice.instances > 0 ? manifestBuilder.instances(manifestChoice.instances) : manifestBuilder;
      manifestBuilder = manifestChoice.memory > 0 ? manifestBuilder.memory(manifestChoice.memory) : manifestBuilder;
      manifestBuilder = manifestBuilder.noRoute(manifestChoice.noRoute);
      manifestBuilder = !CollectionUtils.isEmpty(manifestChoice.servicesNames) ? manifestBuilder.services(manifestChoice.servicesNames.stream().map(serviceName -> serviceName.name).collect(Collectors.toList())) : manifestBuilder;
      manifestBuilder = !StringUtils.isBlank(manifestChoice.stack) ? manifestBuilder.stack(manifestChoice.stack) : manifestBuilder;
      manifestBuilder = manifestChoice.timeout > 0 ? manifestBuilder.timeout(manifestChoice.timeout) : manifestBuilder;
      return Collections.singletonList(manifestBuilder.build());
    }

    private void printStagingLogs(CloudFoundryOperations cloudFoundryOperations,
                                  final TaskListener listener, String appName) {
      cloudFoundryOperations.applications().logs(LogsRequest.builder().name(appName).recent(Boolean.TRUE).build())
        .timeout(Duration.ofSeconds(pluginTimeout))
        .doOnNext(applicationLog -> listener.getLogger().println(applicationLog.getMessage()))
        .blockLast();

    }

    private static final Pattern TARGET_PATTERN = Pattern.compile("((?<scheme>https?)://)?(?<targetFqdn>[^:/]+)(:(?<port>\\d+))?(/.*)?");

    protected URL targetUrl() throws MalformedURLException {
      String scheme = "https";
      String targetFqdn = target;
      Integer port = null;
      Matcher targetMatcher = TARGET_PATTERN.matcher(target);
      if (targetMatcher.find()) {
        if (targetMatcher.group("scheme") != null) {
          scheme = targetMatcher.group("scheme");
        }
        targetFqdn = targetMatcher.group("targetFqdn");
        String portNumber = targetMatcher.group("port");
        if (portNumber != null) {
          port = Integer.parseInt(portNumber);
        }
      }
      StringBuilder sb = new StringBuilder(scheme).append("://").append(targetFqdn);
      if (port != null) {
        sb = sb.append(":").append(port.toString());
      }
      return new URL(sb.toString());
    }

    protected ConnectionContext createConnectionContext() throws MalformedURLException {
      String scheme = "https";
      Boolean secure = null;
      String targetFqdn = target;
      Integer port = null;
      Matcher targetMatcher = TARGET_PATTERN.matcher(target);
      if (targetMatcher.find()) {
        if (targetMatcher.group("scheme") != null) {
          scheme = targetMatcher.group("scheme");
          secure = Boolean.valueOf(scheme.endsWith("s"));
          if (!secure.booleanValue()) {
            port = Integer.valueOf(80); // set the default http port
          }
        }
        targetFqdn = targetMatcher.group("targetFqdn");
        String portNumber = targetMatcher.group("port");
        if (portNumber != null) {
          port = Integer.parseInt(portNumber);
        }
      }
      DefaultConnectionContext.Builder builder = DefaultConnectionContext.builder()
                .apiHost(targetFqdn)
                .proxyConfiguration(CloudFoundryUtils.buildProxyConfiguration(targetUrl()))
                .skipSslValidation(selfSigned);
      if (secure != null) {
        builder = builder.secure(secure.booleanValue());
      }
      if (port != null) {
        builder = builder.port(port);
      }
      return builder.build();
    }

}
