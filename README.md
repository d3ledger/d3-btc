# D3 Bitcoin

## How to run notary application and services in Bitcoin main net
1) Run common services
2) Create `.wallet` file (ask maintainers how to do that) and put it to desired location
3) Run address generation process using `PROFILE=mainnet ./gradlew runBtcAddressGeneration`
4) Run registration service `PROFILE=mainnet ./gradlew runBtcRegistration`
5) Run notary service `PROFILE=mainnet ./gradlew runBtcDepositWithdrawal`

## Testing Bitcoin
There is a dedicated endpoint for testing purposes. Visit [Swagger](http://127.0.0.1:18981/apidocs) for more details.