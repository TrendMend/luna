{
  description = "Luna: a LiveKit voice assistant agent for the home (Home Assistant, TV, caregivers)";

  inputs = {
    # Same pin the nixos-nuc repo uses, so a deployment there shares store paths with this flake.
    nixpkgs.url = "github:NixOS/nixpkgs/0bb2eedc20e4739f29e8045034f2828ad7054f8f";
    pyproject-nix = {
      url = "github:pyproject-nix/pyproject.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    uv2nix = {
      url = "github:pyproject-nix/uv2nix";
      inputs.pyproject-nix.follows = "pyproject-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    pyproject-build-systems = {
      url = "github:pyproject-nix/build-system-pkgs";
      inputs.pyproject-nix.follows = "pyproject-nix";
      inputs.uv2nix.follows = "uv2nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, pyproject-nix, uv2nix, pyproject-build-systems, ... }:
    let
      inherit (nixpkgs) lib;
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # The Python environment is built from worker/uv.lock, so Nix, Docker and `uv run` use identical versions.
      workspace = uv2nix.lib.workspace.loadWorkspace { workspaceRoot = ./worker; };
      overlay = workspace.mkPyprojectOverlay { sourcePreference = "wheel"; };

      # Provider plugins to include: the same names as the optional extras in pyproject.toml.
      defaultExtras = [ "openai" "deepgram" "cartesia" ];

      pythonSetFor = pkgs:
        let
          python = pkgs.python312;
          # Native wheels (onnxruntime, numpy, livekit's rtc library, ...) are patched to find their
          # libraries by autoPatchelf; add what they need here when a new dependency asks for more.
          nativeLibs = _final: prev:
            lib.mapAttrs (_name: pkg: pkg.overrideAttrs (old: {
              buildInputs = (old.buildInputs or [ ]) ++ [ pkgs.stdenv.cc.cc.lib pkgs.zlib pkgs.libgcc ];
            })) (lib.filterAttrs (n: _: builtins.elem n [ "onnxruntime" "numpy" "livekit" "av" "scipy" "grpcio" "tokenizers" "safetensors" "hf-xet" "lxml" "pillow" "pydantic-core" "aiohttp" "yarl" "multidict" "frozenlist" "propcache" "cryptography" "cffi" "markupsafe" "psutil" "watchfiles" "uvloop" "httptools" "websockets" "pyyaml" "regex" "sentencepiece" "protobuf" ]) prev);
        in
        (pkgs.callPackage pyproject-nix.build.packages { inherit python; }).overrideScope
          (lib.composeManyExtensions [ pyproject-build-systems.overlays.default overlay nativeLibs ]);

      envFor = pkgs: extras:
        (pythonSetFor pkgs).mkVirtualEnv "luna-env" { luna-agent = extras; };
    in
    {
      packages = forAllSystems (pkgs: {
        default = envFor pkgs defaultExtras;
      });

      # Build your own with different provider plugins:
      #   luna.lib.envFor pkgs [ "elevenlabs" "google" ]
      lib.envFor = envFor;

      nixosModules.default = import ./nix/module.nix self;

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.uv pkgs.python312 ];
          env.UV_PYTHON_DOWNLOADS = "never";
        };
      });
    };
}
