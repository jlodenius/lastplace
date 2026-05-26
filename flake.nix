{
  description = "Parking assistant — Android dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;            # android-studio is unfree
            android_sdk.accept_license = true;
          };
        };
      in {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.android-studio   # IDE; bundles its own JDK, manages the SDK
            pkgs.android-tools    # NixOS-native adb / fastboot for the CLI
            pkgs.jdk17            # for ./gradlew from a plain shell, if you want it
          ];

          shellHook = ''
            echo "Parking dev shell. Run 'android-studio' to launch the IDE."
            echo "First launch: let the setup wizard install the SDK (API 34/35)."
          '';
        };
      });
}
