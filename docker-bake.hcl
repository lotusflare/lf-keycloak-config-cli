variable "REPO" {
  default = "111087410577.dkr.ecr.us-east-1.amazonaws.com"
}

variable "TAG" {
  default = null
}

variable "INPUT_IMAGE_NAME" {
  default = null
}

variable "KEYCLOAK_VERSION" {
  default = "26.5.5"
}

variable "KEYCLOAK_CLIENT_VERSION" {
  default = "26.0.8"
}

variable "MAVEN_CLI_OPTS" {
  default = "-ntp -B"
}

function "tag" {
  params = [tag]
  result = "${REPO}/${INPUT_IMAGE_NAME}:${tag}"
}

group "prod" {
  targets = ["prod"]
}

target "prod" {
  context    = "."
  dockerfile = "Dockerfile"
  platforms  = ["linux/amd64", "linux/arm64"]
  tags = [
    tag("${TAG}")
  ]
  args = {
    KEYCLOAK_VERSION        = KEYCLOAK_VERSION
    KEYCLOAK_CLIENT_VERSION = KEYCLOAK_CLIENT_VERSION
    MAVEN_CLI_OPTS          = MAVEN_CLI_OPTS
  }
}
