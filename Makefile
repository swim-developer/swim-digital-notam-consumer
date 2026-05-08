REGISTRY ?= quay.io/masales
TAG      ?= latest
IMAGE    := swim-dnotam-consumer
PLATFORMS := linux/amd64,linux/arm64
MVN_NATIVE := -Dnative -DskipTests \
              -Dquarkus.native.container-build=true \
              -Dquarkus.native.container-runtime=podman
SONAR_URL   ?= http://localhost:9000
SONAR_TOKEN ?=

PARENT_DIR  := $(abspath $(dir $(abspath $(lastword $(MAKEFILE_LIST))))/..)
GITHUB_SSH  := git@github.com:swim-developer

SYNC_DEPS := swim-developer-root swim-aixm-model swim-developer-framework swim-developer-extensions

.PHONY: help sync pull pull-deps install-deps deps build test jvm \
        native-amd64 native-arm64 manifest push native \
        sonar security-deps security-image

help:
	@echo ""
	@echo "  swim-dnotam-consumer — available targets"
	@echo "  ─────────────────────────────────────────────────────────"
	@echo ""
	@echo "  Local dev:"
	@echo "    sync               Full setup: pull + pull-deps + install-deps"
	@echo "    pull               Pull this project from remote"
	@echo "    pull-deps          Clone missing deps + pull existing ones in $(PARENT_DIR)"
	@echo "    install-deps       Install all deps into local Maven repository"
	@echo "    build              Compile + package JAR (skips tests)"
	@echo "    test               Unit + integration tests (Testcontainers)"
	@echo "    deps               Show which sibling repos must be installed first"
	@echo ""
	@echo "  Container images  (multi-arch: linux/amd64 + linux/arm64)"
	@echo ""
	@echo "    jvm                JVM multi-arch image — build + push  (fastest build)"
	@echo ""
	@echo "    Distributed native (one machine per arch, then merge):"
	@echo "    native-amd64       Native amd64 image — build + push  (run on amd64)"
	@echo "    native-arm64       Native arm64 image — build + push  (run on arm64)"
	@echo "    manifest           Create multi-arch manifest from registry images"
	@echo "    push               Push manifest to registry"
	@echo "    native             native-amd64 + native-arm64 + manifest + push"
	@echo ""
	@echo "  Quality:"
	@echo "    sonar              SonarQube analysis  (requires SONAR_TOKEN=<token>)"
	@echo "    security-deps      OWASP Dependency-Check"
	@echo "    security-image     Trivy CVE scan on container image"
	@echo ""
	@echo "  Variables: REGISTRY=$(REGISTRY)  TAG=$(TAG)"

sync: pull pull-deps install-deps

pull:
	@echo ""
	@echo "  ── Pull this project ────────────────────────────────────────"
	@git pull --ff-only
	@echo ""

pull-deps:
	@echo ""
	@echo "  ── Ensure sibling dependencies in $(PARENT_DIR) ─────────────"
	@for repo in $(SYNC_DEPS); do \
	  dir="$(PARENT_DIR)/$$repo"; \
	  if [ ! -d "$$dir" ]; then \
	    echo "  CLONE   $$repo"; \
	    git clone "$(GITHUB_SSH)/$$repo.git" "$$dir" --quiet; \
	  else \
	    printf "  PULL    $$repo ... "; \
	    git -C "$$dir" pull --ff-only --quiet 2>&1 && echo "ok" || echo "skipped (local changes or detached HEAD)"; \
	  fi; \
	done
	@echo ""

install-deps:
	@echo ""
	@echo "  ── Install dependencies into local Maven repository ─────────"
	@for repo in $(SYNC_DEPS); do \
	  dir="$(PARENT_DIR)/$$repo"; \
	  if [ ! -d "$$dir" ]; then \
	    echo "  SKIP    $$repo (not found — run: make pull-deps)"; \
	    continue; \
	  fi; \
	  mvn_cmd="mvn"; \
	  [ -f "$$dir/mvnw" ] && mvn_cmd="$$dir/mvnw"; \
	  if [ "$$repo" = "swim-developer-root" ]; then \
	    args="install -N -DskipTests -q"; \
	  else \
	    args="clean install -DskipTests -q"; \
	  fi; \
	  printf "  INSTALL $$repo ... "; \
	  "$$mvn_cmd" -f "$$dir/pom.xml" $$args && echo "ok" || { echo "FAIL"; exit 1; }; \
	done
	@echo ""
	@echo "  Done. Run: make build"
	@echo ""

deps:
	@echo ""
	@echo "  Required sibling repos — install each to local Maven repo before building:"
	@echo ""
	@echo "    git clone https://github.com/swim-developer/swim-developer-root"
	@echo "    cd swim-developer-root && ./mvnw install -N -DskipTests"
	@echo ""
	@echo "    git clone https://github.com/swim-developer/swim-aixm-model"
	@echo "    cd swim-aixm-model && ./mvnw clean install -DskipTests"
	@echo ""
	@echo "    git clone https://github.com/swim-developer/swim-developer-framework"
	@echo "    cd swim-developer-framework && ./mvnw clean install -DskipTests"
	@echo ""
	@echo "    git clone https://github.com/swim-developer/swim-developer-extensions"
	@echo "    cd swim-developer-extensions && ./mvnw clean install -DskipTests"
	@echo ""

build:
	./mvnw clean package -DskipTests

test:
	./mvnw verify -DskipITs=false

# ─── JVM multi-arch ──────────────────────────────────────────────────────────

jvm: build
	@podman rmi $(REGISTRY)/$(IMAGE):$(TAG) >/dev/null 2>&1 || true
	@podman manifest rm $(REGISTRY)/$(IMAGE):$(TAG) >/dev/null 2>&1 || true
	podman manifest create $(REGISTRY)/$(IMAGE):$(TAG)
	podman build --no-cache --platform $(PLATFORMS) \
		-f src/main/docker/Containerfile.jvm \
		--manifest $(REGISTRY)/$(IMAGE):$(TAG) .
	podman manifest push --all $(REGISTRY)/$(IMAGE):$(TAG) \
		docker://$(REGISTRY)/$(IMAGE):$(TAG)
	@echo ""
	@echo "Pushed: $(REGISTRY)/$(IMAGE):$(TAG)  (JVM multi-arch)"

# ─── Native distributed ──────────────────────────────────────────────────────

native-amd64:
	./mvnw clean package $(MVN_NATIVE) \
		-Dquarkus.native.container-runtime-options=--platform,linux/amd64
	podman build --no-cache --platform linux/amd64 \
		-f src/main/docker/Containerfile.native-micro \
		-t $(REGISTRY)/$(IMAGE):$(TAG)-amd64 .
	podman push $(REGISTRY)/$(IMAGE):$(TAG)-amd64
	@echo "Pushed: $(REGISTRY)/$(IMAGE):$(TAG)-amd64"

native-arm64:
	./mvnw clean package $(MVN_NATIVE) \
		-Dquarkus.native.container-runtime-options=--platform,linux/arm64
	podman build --no-cache --platform linux/arm64 \
		-f src/main/docker/Containerfile.native-micro \
		-t $(REGISTRY)/$(IMAGE):$(TAG)-arm64 .
	podman push $(REGISTRY)/$(IMAGE):$(TAG)-arm64
	@echo "Pushed: $(REGISTRY)/$(IMAGE):$(TAG)-arm64"

manifest:
	@podman rmi $(REGISTRY)/$(IMAGE):$(TAG) >/dev/null 2>&1 || true
	@podman manifest rm $(REGISTRY)/$(IMAGE):$(TAG) >/dev/null 2>&1 || true
	podman manifest create $(REGISTRY)/$(IMAGE):$(TAG) \
		docker://$(REGISTRY)/$(IMAGE):$(TAG)-amd64 \
		docker://$(REGISTRY)/$(IMAGE):$(TAG)-arm64
	@echo "Manifest ready: $(REGISTRY)/$(IMAGE):$(TAG)  (linux/amd64 + linux/arm64)"
	@echo "Next: make push"

push:
	podman manifest push --all $(REGISTRY)/$(IMAGE):$(TAG) \
		docker://$(REGISTRY)/$(IMAGE):$(TAG)
	@echo "Pushed: $(REGISTRY)/$(IMAGE):$(TAG)  (multi-arch)"

native: native-amd64 native-arm64 manifest push

# ─── Quality ─────────────────────────────────────────────────────────────────

sonar:
	./mvnw clean verify sonar:sonar \
		-DskipITs=false \
		-Dit.test=DnotamConsumerIT \
		-Dsonar.host.url=$(SONAR_URL) \
		$(if $(SONAR_TOKEN),-Dsonar.login=$(SONAR_TOKEN),) \
		-Dsonar.projectKey=$(IMAGE) \
		-Dsonar.projectName=$(IMAGE)

security-deps:
	./mvnw org.owasp:dependency-check-maven:aggregate \
		-DfailBuildOnCVSS=7 -Dformats=HTML,JSON -DskipTests \
		-DsuppressionFile=owasp-suppressions.xml
	@echo "Report: target/dependency-check-report.html"

security-image:
	@command -v trivy >/dev/null 2>&1 || \
		{ echo "trivy not found — install: brew install trivy"; exit 1; }
	trivy image --severity HIGH,CRITICAL $(REGISTRY)/$(IMAGE):$(TAG)
