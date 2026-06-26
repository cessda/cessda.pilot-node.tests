/*
 * Copyright © 2026 CESSDA ERIC (support@cessda.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
pipeline {

    agent {
        label 'jnlp-himem'
    }

    environment {
        PRODUCT_NAME = 'pilot-node-tests'
        IMAGE_NAME = "${DOCKER_ARTIFACT_REGISTRY}/${PRODUCT_NAME}"
        DOCKER_IMAGE = "${IMAGE_NAME}:${GIT_COMMIT}"
    }

    stages {
        stage('Compile Java') {
            agent {
                docker {
                    image 'eclipse-temurin:17'
                    reuseNode true
                }
            }
            steps {
                withMaven {
                    sh "./mvnw verify"
                }
            }
        }
        stage('Build Docker Image') {
            steps {
                withMaven {
                    sh "./mvnw spring-boot:build-image-no-fork -Dspring-boot.build-image.imageName=${DOCKER_IMAGE}"
                }
            }
        }
        stage('Push Docker Image') {
            steps {
                sh "gcloud auth configure-docker ${ARTIFACT_REGISTRY_HOST}"
                sh "docker push ${DOCKER_IMAGE}"
            }
            when { branch 'main' }
        }
        stage('Deploy Pilot Node Tests') {
            steps {
                build job: 'cessda.resource-catalogue.deploy/main', parameters: [string(name: 'TESTS_IMAGE_TAG', value: GIT_COMMIT)], wait: false
            }
        }
    }
}
