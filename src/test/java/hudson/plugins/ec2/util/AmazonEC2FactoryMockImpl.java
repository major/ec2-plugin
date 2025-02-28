package hudson.plugins.ec2.util;

import static org.mockito.Mockito.mock;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.EbsBlockDevice;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.InstanceStateChange;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotInstanceState;
import com.amazonaws.services.ec2.model.SpotInstanceType;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

@Extension
public class AmazonEC2FactoryMockImpl implements AmazonEC2Factory {

    /**
     * public static to allow supplying own mock to tests.
     */
    public static AmazonEC2Client mock;

    public static List<Instance> instances;

    /**
     * public static to provide a default mock for modifications from tests.
     *
     * @return mocked AmazonEC2
     */
    public static AmazonEC2Client createAmazonEC2Mock() {
        instances = new ArrayList<>(); // Reset for each new mock. In the real world, the client is stateless, but this
        // is convenient for testing.
        return createAmazonEC2Mock(null);
    }

    /**
     * public static to provide a default mock for modifications from tests.
     *
     * @param defaultAnswer
     *            nullable
     * @return mocked AmazonEC2
     */
    public static AmazonEC2Client createAmazonEC2Mock(@Nullable Answer defaultAnswer) {
        AmazonEC2Client mock;
        if (defaultAnswer != null) {
            mock = mock(AmazonEC2Client.class, defaultAnswer);
        } else {
            mock = mock(AmazonEC2Client.class);
            mockDescribeRegions(mock);
            mockDescribeInstances(mock);
            mockDescribeImages(mock);
            mockDescribeKeyPairs(mock);
            mockDescribeSecurityGroups(mock);
            mockDescribeSubnets(mock);
            mockRunInstances(mock);
            mockTerminateInstances(mock);
            mockDescribeSpotInstanceRequests(mock);
        }
        return mock;
    }

    private static void mockDescribeRegions(AmazonEC2Client mock) {
        DescribeRegionsResult describeRegionsResultMock = mock(DescribeRegionsResult.class);
        Mockito.doReturn(Collections.singletonList(new Region().withRegionName(EC2Cloud.DEFAULT_EC2_HOST)))
                .when(describeRegionsResultMock)
                .getRegions();
        Mockito.doReturn(describeRegionsResultMock).when(mock).describeRegions();
    }

    private static void mockDescribeInstances(AmazonEC2Client mock) {
        Mockito.doCallRealMethod().when(mock).describeInstances(); // This will just pass on to
        // describeInstances(describeInstancesRequest)
        Mockito.doAnswer(invocationOnMock -> {
                    DescribeInstancesRequest request = invocationOnMock.getArgument(0);
                    if (request.getInstanceIds() != null
                            && !request.getInstanceIds().isEmpty()) {
                        return new DescribeInstancesResult()
                                .withReservations(new Reservation()
                                        .withInstances(instances.stream()
                                                .filter(instance ->
                                                        request.getInstanceIds().contains(instance.getInstanceId()))
                                                .collect(Collectors.toList())));
                    }
                    return new DescribeInstancesResult().withReservations(new Reservation().withInstances(instances));
                })
                .when(mock)
                .describeInstances(Mockito.any(DescribeInstancesRequest.class));
    }

    private static void mockDescribeSpotInstanceRequests(AmazonEC2Client mock) {
        DescribeSpotInstanceRequestsResult describeSpotInstanceRequestsResult =
                new DescribeSpotInstanceRequestsResult();
        List<SpotInstanceRequest> spotInstanceRequests = new ArrayList<>();
        for (Instance instance : instances) {
            SpotInstanceRequest spotInstanceRequest = new SpotInstanceRequest()
                    .withInstanceId(instance.getInstanceId())
                    .withTags(instance.getTags())
                    .withState(SpotInstanceState.Active)
                    .withType(SpotInstanceType.OneTime);
            spotInstanceRequests.add(spotInstanceRequest);
        }

        Mockito.doAnswer(invocationOnMock -> {
                    DescribeSpotInstanceRequestsRequest request = invocationOnMock.getArgument(0);
                    int paginationSize = request.getMaxResults();
                    if (paginationSize == 0) {
                        paginationSize = instances.size();
                    }
                    if (instances.size() > paginationSize && request.getNextToken() == null) {
                        describeSpotInstanceRequestsResult.setNextToken("token");
                        describeSpotInstanceRequestsResult.setSpotInstanceRequests(
                                spotInstanceRequests.subList(0, 100));
                    } else if (instances.size() <= paginationSize) {
                        describeSpotInstanceRequestsResult.setSpotInstanceRequests(spotInstanceRequests);
                        describeSpotInstanceRequestsResult.setNextToken(null);
                    } else {
                        describeSpotInstanceRequestsResult.setSpotInstanceRequests(
                                spotInstanceRequests.subList(100, spotInstanceRequests.size() - 1));
                        describeSpotInstanceRequestsResult.setNextToken(null);
                    }
                    return describeSpotInstanceRequestsResult;
                })
                .when(mock)
                .describeSpotInstanceRequests(Mockito.any(DescribeSpotInstanceRequestsRequest.class));
    }

    private static void mockDescribeImages(AmazonEC2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    DescribeImagesRequest request = invocationOnMock.getArgument(0);
                    return new DescribeImagesResult()
                            .withImages(request.getImageIds().stream()
                                    .map(AmazonEC2FactoryMockImpl::createMockImage)
                                    .collect(Collectors.toList()));
                })
                .when(mock)
                .describeImages(Mockito.any(DescribeImagesRequest.class));
    }

    private static Image createMockImage(String amiId) {
        return new Image()
                .withImageId(amiId)
                .withRootDeviceType("ebs")
                .withBlockDeviceMappings(
                        new BlockDeviceMapping().withDeviceName("/dev/null").withEbs(new EbsBlockDevice()));
    }

    private static void mockDescribeKeyPairs(AmazonEC2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    KeyPairInfo keyPairInfo = new KeyPairInfo();
                    keyPairInfo.setKeyFingerprint(Jenkins.get()
                            .clouds
                            .get(EC2Cloud.class)
                            .resolvePrivateKey()
                            .getFingerprint());
                    return new DescribeKeyPairsResult().withKeyPairs(keyPairInfo);
                })
                .when(mock)
                .describeKeyPairs();
    }

    private static void mockDescribeSecurityGroups(AmazonEC2Client mock) {
        Mockito.doAnswer(invocationOnMock -> new DescribeSecurityGroupsResult()
                        .withSecurityGroups(new SecurityGroup().withVpcId("whatever")))
                .when(mock)
                .describeSecurityGroups(Mockito.any(DescribeSecurityGroupsRequest.class));
    }

    private static void mockDescribeSubnets(AmazonEC2Client mock) {
        Mockito.doAnswer(invocationOnMock -> new DescribeSubnetsResult().withSubnets(new Subnet()))
                .when(mock)
                .describeSubnets(Mockito.any(DescribeSubnetsRequest.class));
    }

    private static void mockRunInstances(AmazonEC2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    RunInstancesRequest request = invocationOnMock.getArgument(0);
                    List<Tag> tags = request.getTagSpecifications().stream()
                            .map(TagSpecification::getTags)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());

                    List<Instance> localInstances = new ArrayList<>();

                    for (int i = 0; i < request.getMaxCount(); i++) {
                        Instance instance = new Instance()
                                .withInstanceId(String.valueOf(Math.random()))
                                .withInstanceType(request.getInstanceType())
                                .withImageId(request.getImageId())
                                .withTags(tags)
                                .withState(new com.amazonaws.services.ec2.model.InstanceState()
                                        .withName(InstanceStateName.Running))
                                .withLaunchTime(new Date());

                        localInstances.add(instance);
                    }

                    instances.addAll(localInstances);

                    return new RunInstancesResult().withReservation(new Reservation().withInstances(localInstances));
                })
                .when(mock)
                .runInstances(Mockito.any(RunInstancesRequest.class));
    }

    private static void mockTerminateInstances(AmazonEC2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    TerminateInstancesRequest request = invocationOnMock.getArgument(0);
                    List<Instance> instancesToRemove = new ArrayList<>();
                    request.getInstanceIds().forEach(instanceId -> instances.stream()
                            .filter(instance -> instance.getInstanceId().equals(instanceId))
                            .findFirst()
                            .ifPresent(instancesToRemove::add));
                    instances.removeAll(instancesToRemove);
                    return new TerminateInstancesResult()
                            .withTerminatingInstances(instancesToRemove.stream()
                                    .map(instance -> new InstanceStateChange()
                                            .withInstanceId(instance.getInstanceId())
                                            .withPreviousState(new InstanceState().withName(InstanceStateName.Stopping))
                                            .withCurrentState(
                                                    new InstanceState().withName(InstanceStateName.Terminated)))
                                    .collect(Collectors.toList()));
                })
                .when(mock)
                .terminateInstances(Mockito.any(TerminateInstancesRequest.class));
    }

    @Override
    public AmazonEC2 connect(AWSCredentialsProvider credentialsProvider, URL ec2Endpoint) {
        if (mock == null) {
            mock = createAmazonEC2Mock();
        }
        return mock;
    }
}
