
pipeline {
  environment {
    DOCKER_NETWORK = ''
  }
  options {
    skipDefaultCheckout()
    buildDiscarder(logRotator(numToKeepStr: '20'))
    timestamps()
  }
  agent any
  stages {
    stage('Stop same job builds') {
      agent { label 'master' }
      steps {
        script {
          def scmVars = checkout scm
          // need this for develop->master PR cases
          // CHANGE_BRANCH is not defined if this is a branch build
          try {
            scmVars.CHANGE_BRANCH_LOCAL = scmVars.CHANGE_BRANCH
          }
          catch (MissingPropertyException e) {
          }
          if (scmVars.GIT_LOCAL_BRANCH != "develop" && scmVars.CHANGE_BRANCH_LOCAL != "develop") {
            def builds = load ".jenkinsci/cancel-builds-same-job.groovy"
            builds.cancelSameJobBuilds()
          }
        }
      }
    }
    stage('Tests') {
      agent { label 'd3-build-agent' }
      steps {
        script {
          def scmVars = checkout scm
          env.WORKSPACE = pwd()

          DOCKER_NETWORK = "${scmVars.CHANGE_ID}-${scmVars.GIT_COMMIT}-${BUILD_NUMBER}"
          writeFile file: ".env", text: "SUBNET=${DOCKER_NETWORK}"

          withCredentials([usernamePassword(credentialsId: 'nexus-d3-docker', usernameVariable: 'login', passwordVariable: 'password')]) {
            sh "docker login nexus.iroha.tech:19002 -u ${login} -p '${password}'"
          }

          withCredentials([usernamePassword(credentialsId: 'nexus-soramitsu-ro', usernameVariable: 'login', passwordVariable: 'password')]) {
            sh "docker login nexus.iroha.tech:19004 -u ${login} -p '${password}'"
          }

          sh "docker-compose -f deploy/docker-compose.yml -f deploy/docker-compose.ci.yml pull"
          sh(returnStdout: true, script: "docker-compose -f deploy/docker-compose.yml -f deploy/docker-compose.ci.yml up --build -d")
          sh "docker cp d3-btc-node0-${DOCKER_NETWORK}:/usr/bin/bitcoin-cli deploy/bitcoin/"

          iC = docker.image("gradle:4.10.2-jdk8-slim")
          iC.inside("--network='d3-${DOCKER_NETWORK}' -e JVM_OPTS='-Xmx3200m' -e TERM='dumb' -v /var/run/docker.sock:/var/run/docker.sock -v /tmp:/tmp") {
            sh "ln -s deploy/bitcoin/bitcoin-cli /usr/bin/bitcoin-cli"
            sh "gradle dependencies"
            sh "gradle test --info"
            sh "gradle shadowJar"
            sh "gradle dockerfileCreate"
            sh "gradle compileIntegrationTestKotlin --info"
            sh "gradle integrationTest --info"
            sh "gradle d3TestReport"
          }
          if (env.BRANCH_NAME == 'develop') {
            iC.inside("--network='d3-${DOCKER_NETWORK}' -e JVM_OPTS='-Xmx3200m' -e TERM='dumb'") {
              withCredentials([string(credentialsId: 'SONAR_TOKEN', variable: 'SONAR_TOKEN')]){
                sh(script: "./gradlew sonarqube -x test --configure-on-demand \
                  -Dsonar.links.ci=${BUILD_URL} \
                  -Dsonar.github.pullRequest=${env.CHANGE_ID} \
                  -Dsonar.github.disableInlineComments=true \
                  -Dsonar.host.url=https://sonar.soramitsu.co.jp \
                  -Dsonar.login=${SONAR_TOKEN} \
                  ")
                }
            }
          }

          publishHTML (target: [
                        allowMissing: false,
                        alwaysLinkToLastBuild: false,
                        keepAll: true,
                        reportDir: 'build/reports',
                        reportFiles: 'd3-test-report.html',
                        reportName: "D3 test report"
                      ])
        }
      }
      post {
        cleanup {
          sh "mkdir -p build-logs"
          sh """#!/bin/bash
            while read -r LINE; do \
              docker logs \$(echo \$LINE | cut -d ' ' -f1) | gzip -6 > build-logs/\$(echo \$LINE | cut -d ' ' -f2).log.gz; \
            done < <(docker ps --filter "network=d3-${DOCKER_NETWORK}" --format "{{.ID}} {{.Names}}")
          """
          
          sh "tar -zcvf build-logs/notaryBtcIntegrationTest.gz -C notary-btc-integration-test/build/reports/tests integrationTest || true"
          sh "tar -zcvf build-logs/dokka.gz -C build/reports dokka || true"
          archiveArtifacts artifacts: 'build-logs/*.gz'
          sh "docker-compose -f deploy/docker-compose.yml -f deploy/docker-compose.ci.yml down"
          cleanWs()
        }
      }
    }
    stage('Build and push docker images') {
      agent { label 'd3-build-agent' }
      steps {
        script {
          def scmVars = checkout scm
          if (env.BRANCH_NAME ==~ /(master|develop|reserved)/ || env.TAG_NAME) {
                withCredentials([usernamePassword(credentialsId: 'nexus-d3-docker', usernameVariable: 'login', passwordVariable: 'password')]) {
                  TAG = env.TAG_NAME ? env.TAG_NAME : env.BRANCH_NAME
                  iC = docker.image("gradle:4.10.2-jdk8-slim")
                  iC.inside(" -e JVM_OPTS='-Xmx3200m' -e TERM='dumb'"+
                  " -v /var/run/docker.sock:/var/run/docker.sock -v /tmp:/tmp"+
                  " -e DOCKER_REGISTRY_URL='https://nexus.iroha.tech:19002'"+
                  " -e DOCKER_REGISTRY_USERNAME='${login}'"+
                  " -e DOCKER_REGISTRY_PASSWORD='${password}'"+
                  " -e TAG='${TAG}'") {
                    sh "gradle shadowJar"
                    sh "gradle dockerPush"
                  }
                 }
              }
        }
      }
    }
  }
}
