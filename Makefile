.PHONY: all build package install run verify clean

GRADLEW := ./gradlew

all: package

build:
	$(GRADLEW) compileKotlin

package: build
	rm -f build/distributions/*.zip
	$(GRADLEW) buildPlugin
	@echo "Plugin package built at:"
	@ls -1 build/distributions/*.zip

# Rider has no CLI to install a plugin zip; install it from disk:
#   Settings > Plugins > (gear) > Install Plugin from Disk...
install: package
	@echo "Install this zip via Rider > Settings > Plugins > Install Plugin from Disk:"
	@ls -1 build/distributions/*.zip

# Launch a sandbox Rider with the plugin loaded (dev run).
run:
	$(GRADLEW) runIde

verify:
	$(GRADLEW) verifyPlugin

clean:
	$(GRADLEW) clean
	rm -rf build/ .intellijPlatform/
