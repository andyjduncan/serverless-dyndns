======================
Serverless Dynamic DNS
======================

A dynamic DNS service using AWS API Gateway, Lambda and Route53.  The function
is written in Groovy and deployed with the Serverless framework.

---------
Deploying
---------

IAM Permissions
===============

The ``iam.yml`` file contains a Cloudformation stack which defines two IAM
policies.  One is used to start the Serverless deployment, the other is used
by Cloudformation to do the deployment.  Both policies have the minimum
permissions needed to deploy the service.
Deploy the stack under the name ``DynDnsIam`` and associate the
``ServerlessDeploymentPolicy`` policy with the user doing the deployment.  The
Serverless framework will automatically pick up the policy from the stack
output.

Build and deploy
================

The function is built and package using gradle:

  ``./gradlew shadowJar``

The service is deployed using the Serverless framework:

  ``npx server deploy``

Continuous Integration
======================

There is a CircleCI configuration to build all branches, and deploy only from
master.  This can be used in conjunction with Dependabot to keep dependencies
up to date.

-------------
Configuration
-------------

The service is configured through SSM parameters.  The keys are prefixed with
the service name and stage (defaulting to ``dyn-dns`` and ``dev``).  Different
stages can be used for different host names, for example.

================  ============  ===========================================
Parameter         Type          Value
================  ============  ===========================================
``hostname``      String        The FQDN to update
``sharedSecret``  SecureString  The shared secret used for signing requests
``hostedZoneId``  String        Route53 zone id to update
================  ============  ===========================================

-----
Usage
-----

Calling the service is as simple as making a ``POST`` request to the service
endpoint.  The endpoint is printed at the end of the deployment, or is
available as an output on the Serverless Cloudformation stack.
The request takes a single parameter, ``signature``.  This is the sha256 hash
of the hostname and shared secret, joined with a colon.  The request could be
made in a terminal as follows::

  SIGNATURE=$(echo -n '<hostname>:<sharedSecret>' | sha256sum | awk '{print $1}')
  curl --request POST --url 'https://<myapigateway.region>.amazonaws.com/<stage>/update?signature='$SIGNATURE

Substitute the values set in parameter store for ``hostname`` and
``sharedSecret``, and the service endpoint in the curl request.
