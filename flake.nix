# flake.nix
{

  description = "Java 11 + Maven development environment (Flake) + Async-profiler";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:

    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        asyncProfilerLib = "${pkgs.async-profiler}/lib/libasyncProfiler.so";
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk11_headless
            maven
            async-profiler
          ];

          shellHook = ''
            export JAVA_HOME=${pkgs.jdk11_headless.home}
            export ASYNC_PROFILER_LIB=${asyncProfilerLib}
            echo "Java 11 Environment"
            echo "Java: $(java -version 2>&1 | head -1)"
            echo "Maven: $(mvn --version | head -1)"
            echo "Async Profiler lib (ASYNC_PROFILER_LIB): $ASYNC_PROFILER_LIB"
          '';
        };
      }
    );

}
