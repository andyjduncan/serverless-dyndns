package com.adjectivecolournoun.dyndns

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import software.amazon.awssdk.services.route53.Route53Client
import software.amazon.awssdk.services.route53.model.Change
import software.amazon.awssdk.services.route53.model.RRType
import software.amazon.awssdk.services.route53.model.ResourceRecord

import java.util.function.Consumer

import static software.amazon.awssdk.regions.Region.AWS_GLOBAL
import static software.amazon.awssdk.services.route53.model.ChangeAction.UPSERT

class DynDnsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final Route53Client route53

    @SuppressWarnings('unused')
    DynDnsHandler() {
        route53 = Route53Client.builder().region(AWS_GLOBAL).build()
    }

    DynDnsHandler(Route53Client route53) {
        this.route53 = route53
    }

    @Override
    APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        if (!input?.queryStringParameters?.containsKey('signature')) {
            return new APIGatewayProxyResponseEvent().withStatusCode(400)
        }

        def signature = input.queryStringParameters['signature']

        if (!signatureValid(signature)) {
            return new APIGatewayProxyResponseEvent().withStatusCode(403)
        }

        def ipAddress = input.headers['X-Forwarded-For'].split(',').first()

        updateRoute53(ipAddress)

        new APIGatewayProxyResponseEvent().withStatusCode(204)
    }

    private static boolean signatureValid(String givenSignature) {
        def hostname = System.getenv('HOSTNAME')
        def sharedSecret = System.getenv('SHARED_SECRET')

        def expectedSignature = "$hostname:$sharedSecret".sha256()

        expectedSignature == givenSignature
    }

    private updateRoute53(String ipAddress) {
        def hostname = System.getenv('HOSTNAME')
        def hostedZoneId = System.getenv('HOSTED_ZONE_ID')

        println "Setting $hostname to $ipAddress"

        println route53.changeResourceRecordSets({ request ->
            request.hostedZoneId(hostedZoneId)
                    .changeBatch({ batch ->
                        batch.changes({ change ->
                            change.action(UPSERT)
                                    .resourceRecordSet({ rrs ->
                                        rrs.name(hostname)
                                                .type(RRType.A)
                                                .ttl(3600L)
                                                .resourceRecords({ rr ->
                                                    rr.value(ipAddress)
                                                } as Consumer<ResourceRecord.Builder>)
                                    })
                        } as Consumer<Change.Builder>)
                    })
        }).changeInfo().statusAsString()
    }

}
