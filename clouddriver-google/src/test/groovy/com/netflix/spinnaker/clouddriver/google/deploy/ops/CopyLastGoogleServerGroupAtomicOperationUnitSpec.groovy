/*
 * Copyright 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.deploy.ops

import com.google.api.services.compute.Compute
import com.google.api.services.compute.model.Image
import com.google.api.services.compute.model.InstanceProperties
import com.google.api.services.compute.model.InstanceTemplate
import com.google.api.services.compute.model.Scheduling
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult
import com.netflix.spinnaker.clouddriver.google.GoogleConfiguration
import com.netflix.spinnaker.clouddriver.google.deploy.GCEUtil
import com.netflix.spinnaker.clouddriver.google.deploy.description.BaseGoogleInstanceDescription
import com.netflix.spinnaker.clouddriver.google.deploy.description.BasicGoogleDeployDescription
import com.netflix.spinnaker.clouddriver.google.deploy.handlers.BasicGoogleDeployHandler
import com.netflix.spinnaker.clouddriver.google.model.GoogleAutoscalingPolicy
import com.netflix.spinnaker.clouddriver.google.model.GoogleDisk
import com.netflix.spinnaker.clouddriver.google.model.GoogleNetwork
import com.netflix.spinnaker.clouddriver.google.model.GoogleServerGroup
import com.netflix.spinnaker.clouddriver.google.model.GoogleSubnet
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleClusterProvider
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class CopyLastGoogleServerGroupAtomicOperationUnitSpec extends Specification {
  private static final String ACCOUNT_NAME = "auto"
  private static final String PROJECT_NAME = "my_project"
  private static final String APPLICATION_NAME = "myapp"
  private static final String STACK_NAME = "dev"
  private static final String ANCESTOR_SERVER_GROUP_NAME = "$APPLICATION_NAME-$STACK_NAME-v000"
  private static final String NEW_SERVER_GROUP_NAME = "$APPLICATION_NAME-$STACK_NAME-v001"
  private static final String IMAGE = "debian-7-wheezy-v20141108"
  private static final String INSTANCE_TYPE = "f1-micro"
  private static final String INSTANCE_TEMPLATE_NAME = "myapp-dev-v000-${System.currentTimeMillis()}"
  private static final String REGION = "us-central1"
  private static final Map<String, String> INSTANCE_METADATA =
          ["startup-script": "apt-get update && apt-get install -y apache2 && hostname > /var/www/index.html",
           "testKey": "testValue"]
  private static final String HTTP_SERVER_TAG = "http-server"
  private static final String HTTPS_SERVER_TAG = "https-server"
  private static final List<String> TAGS = ["orig-tag-1", "orig-tag-2", HTTP_SERVER_TAG, HTTPS_SERVER_TAG]
  private static final String SERVICE_ACCOUNT_EMAIL = "default"
  private static final List<String> AUTH_SCOPES = ["compute", "logging.write"]
  private static final List<String> LOAD_BALANCERS = ["testlb-east-1", "testlb-east-2"]
  private static final String ZONE = "us-central1-b"

  private static final long DISK_SIZE_GB = 100
  private static final String DISK_TYPE = "pd-standard"
  private static final GoogleDisk DISK_PD_STANDARD = new GoogleDisk(type: DISK_TYPE, sizeGb: DISK_SIZE_GB)
  private static final String DEFAULT_NETWORK_NAME = "default"
  private static final String SUBNET_NAME = "some-subnet"
  private static final String ACCESS_CONFIG_NAME = "External NAT"
  private static final String ACCESS_CONFIG_TYPE = "ONE_TO_ONE_NAT"

  private def computeMock
  private def credentials

  private def sourceImage
  private def network
  private def subnet
  private def attachedDisks
  private def networkInterface
  private def instanceMetadata
  private def tags
  private def scheduling
  private def serviceAccount
  private def instanceProperties
  private def instanceTemplate
  private def serverGroup

  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))
  }

  def setup() {
    computeMock = Mock(Compute)
    credentials = new GoogleNamedAccountCredentials.Builder().project(PROJECT_NAME).compute(computeMock).build()

    sourceImage = new Image(selfLink: IMAGE)
    network = new GoogleNetwork(selfLink: DEFAULT_NETWORK_NAME)
    subnet = new GoogleSubnet(selfLink: SUBNET_NAME)
    attachedDisks = GCEUtil.buildAttachedDisks(PROJECT_NAME,
                                               null,
                                               sourceImage,
                                               [DISK_PD_STANDARD],
                                               false,
                                               INSTANCE_TYPE,
                                               new GoogleConfiguration.DeployDefaults())
    networkInterface = GCEUtil.buildNetworkInterface(network, subnet, ACCESS_CONFIG_NAME, ACCESS_CONFIG_TYPE)
    instanceMetadata = GCEUtil.buildMetadataFromMap(INSTANCE_METADATA)
    tags = GCEUtil.buildTagsFromList(TAGS)
    scheduling = new Scheduling(preemptible: false,
                                automaticRestart: true,
                                onHostMaintenance: "MIGRATE")
    serviceAccount = GCEUtil.buildServiceAccount(SERVICE_ACCOUNT_EMAIL, AUTH_SCOPES)
    instanceProperties = new InstanceProperties(machineType: INSTANCE_TYPE,
                                                disks: attachedDisks,
                                                networkInterfaces: [networkInterface],
                                                metadata: instanceMetadata,
                                                tags: tags,
                                                scheduling: scheduling,
                                                serviceAccounts: serviceAccount)
    instanceTemplate = new InstanceTemplate(name: INSTANCE_TEMPLATE_NAME,
                                            properties: instanceProperties)

    serverGroup = new GoogleServerGroup(name: ANCESTOR_SERVER_GROUP_NAME,
                                        zone: ZONE,
                                        asg: [(GoogleServerGroup.View.REGIONAL_LOAD_BALANCER_NAMES): LOAD_BALANCERS,
                                            desiredCapacity: 2],
                                        launchConfig: [instanceTemplate: instanceTemplate],
                                        autoscalingPolicy: [coolDownPeriodSec: 45,
                                                            minNumReplicas: 2,
                                                            maxNumReplicas: 5],
                                        autoHealingPolicy: [healthCheck: 'some-health-check',
                                                            initialDelaySec: 600]).view
  }

  @Unroll
  void "operation builds description based on ancestor server group; overrides everything"() {
    setup:
      def description = new BasicGoogleDeployDescription(application: APPLICATION_NAME,
                                                         stack: STACK_NAME,
                                                         targetSize: targetSize,
                                                         image: "backports-$IMAGE",
                                                         instanceType: "n1-standard-8",
                                                         disks: [new GoogleDisk(type: "pd-ssd", sizeGb: 250)],
                                                         regional: false,
                                                         region: REGION,
                                                         zone: ZONE,
                                                         instanceMetadata: ["differentKey": "differentValue"],
                                                         tags: ["new-tag-1", "new-tag-2"],
                                                         preemptible: true,
                                                         automaticRestart: false,
                                                         onHostMaintenance: BaseGoogleInstanceDescription.OnHostMaintenance.TERMINATE,
                                                         serviceAccountEmail: "something@test.iam.gserviceaccount.com",
                                                         authScopes: ["some-scope", "some-other-scope"],
                                                         network: "other-network",
                                                         subnet: "other-subnet",
                                                         loadBalancers: ["testlb-west-1", "testlb-west-2"],
                                                         autoscalingPolicy:
                                                             new GoogleAutoscalingPolicy(
                                                                 coolDownPeriodSec: 90,
                                                                 minNumReplicas: 5,
                                                                 maxNumReplicas: 9
                                                             ),
                                                         autoHealingPolicy:
                                                             new BasicGoogleDeployDescription.AutoHealingPolicy(
                                                                 healthCheck: 'different-health-check',
                                                                 initialDelaySec: 900,
                                                                 maxUnavailable: [fixed: 5]
                                                             ),
                                                         source: [region: REGION,
                                                                  serverGroupName: ANCESTOR_SERVER_GROUP_NAME],
                                                         accountName: ACCOUNT_NAME,
                                                         credentials: credentials)
      def googleClusterProviderMock = Mock(GoogleClusterProvider)
      def basicGoogleDeployHandlerMock = Mock(BasicGoogleDeployHandler)
      def newDescription = description.clone()
      def deploymentResult = new DeploymentResult(serverGroupNames: ["$REGION:$NEW_SERVER_GROUP_NAME"])
      @Subject def operation = new CopyLastGoogleServerGroupAtomicOperation(description)
      operation.googleClusterProvider = googleClusterProviderMock
      operation.basicGoogleDeployHandler = basicGoogleDeployHandlerMock

    when:
      operation.operate([])

    then:
      1 * googleClusterProviderMock.getServerGroup(ACCOUNT_NAME, REGION, ANCESTOR_SERVER_GROUP_NAME) >> serverGroup

      1 * basicGoogleDeployHandlerMock.handle(newDescription, _) >> deploymentResult

    where:
      targetSize << [4, 0]
  }

  void "operation builds description based on ancestor server group; overrides nothing"() {
    setup:
      def description = new BasicGoogleDeployDescription(source: [region: REGION,
                                                                  serverGroupName: ANCESTOR_SERVER_GROUP_NAME],
                                                         region: REGION,
                                                         accountName: ACCOUNT_NAME,
                                                         credentials: credentials)
      def googleClusterProviderMock = Mock(GoogleClusterProvider)
      def basicGoogleDeployHandlerMock = Mock(BasicGoogleDeployHandler)
      def newDescription = description.clone()
      newDescription.application = APPLICATION_NAME
      newDescription.stack = STACK_NAME
      newDescription.targetSize = 2
      newDescription.image = IMAGE
      newDescription.instanceType = INSTANCE_TYPE
      newDescription.disks = [DISK_PD_STANDARD]
      newDescription.regional = false
      newDescription.region = REGION
      newDescription.zone = ZONE
      newDescription.instanceMetadata = INSTANCE_METADATA
      newDescription.tags = TAGS
      newDescription.preemptible = false
      newDescription.automaticRestart = true
      newDescription.onHostMaintenance = BaseGoogleInstanceDescription.OnHostMaintenance.MIGRATE
      newDescription.serviceAccountEmail = SERVICE_ACCOUNT_EMAIL
      newDescription.authScopes = AUTH_SCOPES
      newDescription.network = DEFAULT_NETWORK_NAME
      newDescription.subnet = SUBNET_NAME
      newDescription.loadBalancers = LOAD_BALANCERS
      newDescription.autoscalingPolicy = new GoogleAutoscalingPolicy(coolDownPeriodSec: 45,
                                                                     minNumReplicas: 2,
                                                                     maxNumReplicas: 5)
      newDescription.autoHealingPolicy = new BasicGoogleDeployDescription.AutoHealingPolicy(healthCheck: 'some-health-check',
                                                                                            initialDelaySec: 600)

      def deploymentResult = new DeploymentResult(serverGroupNames: ["$REGION:$NEW_SERVER_GROUP_NAME"])
      @Subject def operation = new CopyLastGoogleServerGroupAtomicOperation(description)
      operation.googleClusterProvider = googleClusterProviderMock
      operation.basicGoogleDeployHandler = basicGoogleDeployHandlerMock

    when:
      operation.operate([])

    then:
      1 * googleClusterProviderMock.getServerGroup(ACCOUNT_NAME, REGION, ANCESTOR_SERVER_GROUP_NAME) >> serverGroup

      1 * basicGoogleDeployHandlerMock.handle(newDescription, _) >> deploymentResult
  }
}
