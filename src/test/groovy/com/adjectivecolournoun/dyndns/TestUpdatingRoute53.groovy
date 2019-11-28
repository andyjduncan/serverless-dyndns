package com.adjectivecolournoun.dyndns

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import org.apache.commons.lang3.RandomUtils
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import software.amazon.awssdk.services.route53.Route53Client
import software.amazon.awssdk.services.route53.model.Change
import software.amazon.awssdk.services.route53.model.ChangeAction
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsResponse
import software.amazon.awssdk.services.route53.model.ResourceRecordSet
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

    void 'updates the route 53 zone'() {
        given:
        def ipAddress = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': ipAddress)
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.changeResourceRecordSets({ Consumer consumer ->
            def builder = ChangeResourceRecordSetsRequest.builder()
            consumer.accept(builder)
            ChangeResourceRecordSetsRequest changeRRSRequest = builder.build()
            assert changeRRSRequest.hostedZoneId() == hostedZoneId
            true
        }) >> ChangeResourceRecordSetsResponse.builder()
                .changeInfo({ cb -> cb.status('UPDATING')})
                .build()
    }

    void 'upserts the hostname'() {
        given:
        def ipAddress = randomIpAddress()

        def request = new APIGatewayProxyRequestEvent()
                .withHeaders('X-Forwarded-For': ipAddress)
                .withQueryStringParameters(signature: signature)

        when:
        handler.handleRequest(request, null)

        then:
        1 * route53.changeResourceRecordSets({ Consumer consumer ->
            def builder = ChangeResourceRecordSetsRequest.builder()
            consumer.accept(builder)
            ChangeResourceRecordSetsRequest changeRRSRequest = builder.build()
            assert changeRRSRequest.changeBatch().changes().size() == 1
            def change = changeRRSRequest.changeBatch().changes().first()
            assert change.action() == UPSERT
            def recordSet = change.resourceRecordSet()
            assert recordSet.name() == hostname
            assert recordSet.ttl() == 3600
            true
        }) >> ChangeResourceRecordSetsResponse.builder()
                .changeInfo({ cb -> cb.status('UPDATING')})
                .build()
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
        1 * route53.changeResourceRecordSets({ Consumer consumer ->
            def builder = ChangeResourceRecordSetsRequest.builder()
            consumer.accept(builder)
            ChangeResourceRecordSetsRequest changeRRSRequest = builder.build()
            def change = changeRRSRequest.changeBatch().changes().first()
            def recordSet = change.resourceRecordSet()
            assert recordSet.resourceRecords().first().value() == ipAddress1
            true
        }) >> ChangeResourceRecordSetsResponse.builder()
                .changeInfo({ cb -> cb.status('UPDATING')})
                .build()
    }

    private static String randomIpAddress() {
        "${nextInt(100, 200)}.${nextInt(100, 200)}.${nextInt(100, 200)}.${nextInt(100, 200)}"
    }
}