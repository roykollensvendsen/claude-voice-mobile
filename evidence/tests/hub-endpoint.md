# The hub's endpoint: pairing over the network, and only the hub let in

- `HubEndpointTest` (9 tests) was written first and did not compile: `HubEndpoint`,
  `Pairing.isOpen` and `KeptGate.hubAddress` did not exist. It passed once they did, with no
  test changed.
- `red-endpoint-failure-kind.txt`: the test that a throwing handler is recorded by its class
  name alone did not compile, since `HttpExchange` had no `failed` parameter yet. The test
  throws with "key=secret" in the message and checks that only
  `java.lang.IllegalStateException` comes out.
- Putting `failed` after the handler broke every caller's trailing lambda, which then bound to
  `failed`. The compiler caught it. `failed` now comes before the handler, so the handler stays
  the trailing lambda.
