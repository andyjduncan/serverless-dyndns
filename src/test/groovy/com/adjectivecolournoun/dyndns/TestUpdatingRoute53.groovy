package com.adjectivecolournoun.dyndns

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import software.amazon.awssdk.core.SdkResponse
import software.amazon.awssdk.services.route53.Route53Client
import software.amazon.awssdk.services.route53.model.*
import spock.lang.Specification

import java.util.function.Consumer

import static org.apache.commons.lang3.RandomStringUtils.random
import static org.apache.commons.lang3.RandomUtils.nextInt
import static software.amazon.awssdk.services.route53.model.ChangeAction.UPSERT

class TestUpdatingRoute53 extends Specification {

    @Rule
    public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

    def hostname = random(10)
    def sharedSecret = random(10)
    def signature = "$hostname:$sharedSecret".sha256()

    def hostedZoneId = random(10)

    def route53 = Mock(Route53Client)

    def handler = new DynDnsHandler(route53)

    void setup() {
        environmentVariables.HOSTNAME = hostname
        environmentVariables.SHARED_SECRET = sharedSecret
        environmentVariables.HOSTED_ZONE_ID = hostedZoneId
    }

    void 'updates the hostname record in route53'() {
        given:
        def ipAddress = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': ipAddress)
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.listResourceRecordSets(_ as Consumer<SdkResponse.Builder>) >> missingRecordResponse()
        1 * route53.changeResourceRecordSets({ Consumer consumer ->
            def builder = ChangeResourceRecordSetsRequest.builder()
            consumer.accept(builder)
            ChangeResourceRecordSetsRequest changeRRSRequest = builder.build()

            assert changeRRSRequest.hostedZoneId() == hostedZoneId
            assert changeRRSRequest.changeBatch().changes().size() == 1
            def change = changeRRSRequest.changeBatch().changes().first()
            assert change.action() == UPSERT
            def recordSet = change.resourceRecordSet()
            assert recordSet.name() == hostname
            assert recordSet.ttl() == 3600
            assert recordSet.type() == RRType.A

            true
        }) >> changeResponse()
    }

    void 'checks for the existing hostname record'() {
        given:
        def ipAddress = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': ipAddress)
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.listResourceRecordSets({ Consumer consumer ->
            def builder = ListResourceRecordSetsRequest.builder()
            consumer.accept(builder)
            ListResourceRecordSetsRequest listRRSRequest = builder.build()

            assert listRRSRequest.hostedZoneId() == hostedZoneId
            assert listRRSRequest.startRecordName() == hostname
            assert listRRSRequest.maxItems() == '1'
            assert listRRSRequest.startRecordType() == RRType.A

            true
        }) >> missingRecordResponse()
        1 * route53.changeResourceRecordSets(_) >> changeResponse()
    }

    void 'updates the hostname if the record does not exist'() {
        given:
        def ipAddress = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': ipAddress)
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.listResourceRecordSets(_ as Consumer<SdkResponse.Builder>) >> missingRecordResponse()
        1 * route53.changeResourceRecordSets(_) >> changeResponse()
    }

    void 'updates the hostname if the existing record does not match'() {
        given:
        def ipAddress = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': ipAddress)
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.listResourceRecordSets(_) >> ListResourceRecordSetsResponse.builder().
                resourceRecordSets({ rrs ->
                    rrs.type(RRType.A)
                    rrs.resourceRecords({ rr ->
                        rr.value(randomIpAddress())
                    } as Consumer<ResourceRecord.Builder>)
                } as Consumer<ResourceRecordSet.Builder>).build()
        1 * route53.changeResourceRecordSets(_) >> changeResponse()
    }

    void 'does not update if the record already matches'() {
        given:
        def ipAddress = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': ipAddress)
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.listResourceRecordSets(_) >> ListResourceRecordSetsResponse.builder()
                .resourceRecordSets({ rrs ->
                    rrs.type(RRType.A)
                    rrs.resourceRecords({ rr ->
                        rr.value(ipAddress)
                    } as Consumer<ResourceRecord.Builder>)
                } as Consumer<ResourceRecordSet.Builder>).build()
        0 * route53.changeResourceRecordSets(_)
    }

    void 'uses the first forwarded for value'() {
        given:
        def ipAddress1 = randomIpAddress()
        def ipAddress2 = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': "$ipAddress1, $ipAddress2")
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.listResourceRecordSets(_ as Consumer<SdkResponse.Builder>) >> missingRecordResponse()
        1 * route53.changeResourceRecordSets({ Consumer consumer ->
            def builder = ChangeResourceRecordSetsRequest.builder()
            consumer.accept(builder)
            ChangeResourceRecordSetsRequest changeRRSRequest = builder.build()
            def change = changeRRSRequest.changeBatch().changes().first()
            def recordSet = change.resourceRecordSet()
            assert recordSet.resourceRecords().first().value() == ipAddress1
            true
        }) >> changeResponse()
    }

    private static String randomIpAddress() {
        "${nextInt(100, 200)}.${nextInt(100, 200)}.${nextInt(100, 200)}.${nextInt(100, 200)}"
    }

    private static Route53Response changeResponse() {
        ChangeResourceRecordSetsResponse.builder()
                .changeInfo({ cb -> cb.status('UPDATING') })
                .build()
    }

    private static Route53Response missingRecordResponse() {
        ListResourceRecordSetsResponse.builder()
                .resourceRecordSets({ rrs ->
                    rrs.type(RRType.NS)
                } as Consumer<ResourceRecordSet.Builder>)
                .build()
    }
}