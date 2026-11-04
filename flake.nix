{
  description = "Luna: a LiveKit voice assistant for the home (worker, listener, web UI)";

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

      # Native wheels (onnxruntime, numpy, livekit's rtc library, ...) are patched to find their libraries by
      # autoPatchelf; add what they need here when a new dependency asks for more.
      nativeWheels = [ "onnxruntime" "numpy" "livekit" "av" "scipy" "scikit-learn" "grpcio" "tokenizers" "safetensors" "hf-xet"
                       "lxml" "pillow" "pydantic-core" "aiohttp" "yarl" "multidict" "frozenlist" "propcache" "cryptography"
                       "cffi" "markupsafe" "psutil" "watchfiles" "uvloop" "httptools" "websockets" "pyyaml" "regex"
                       "sentencepiece" "protobuf" ];

      # Each Python project (worker/, listener/) is built from its own uv.lock, so Nix, Docker and `uv run` use
      # identical versions.
      pythonSetFor = pkgs: root: extra:
        let
          ws = uv2nix.lib.workspace.loadWorkspace { workspaceRoot = root; };
          overlay = ws.mkPyprojectOverlay { sourcePreference = "wheel"; };
          nativeLibs = _final: prev:
            lib.mapAttrs (_name: pkg: pkg.overrideAttrs (old: {
              buildInputs = (old.buildInputs or [ ]) ++ [ pkgs.stdenv.cc.cc.lib pkgs.zlib pkgs.libgcc ];
            })) (lib.filterAttrs (n: _: builtins.elem n nativeWheels) prev);
        in
        (pkgs.callPackage pyproject-nix.build.packages { python = pkgs.python312; }).overrideScope
          (lib.composeManyExtensions ([ pyproject-build-systems.overlays.default overlay nativeLibs ] ++ extra));

      # sounddevice looks PortAudio up with ctypes' find_library, which finds nothing on NixOS: point it at the store path.
      portaudioFix = pkgs: _final: prev: {
        sounddevice = prev.sounddevice.overrideAttrs (old: {
          postFixup = (old.postFixup or "") + ''
            sed -i "s|_libname = _find_library(_libname)|_libname = '${pkgs.portaudio}/lib/libportaudio.so.2'|" \
              $out/lib/python3.12/site-packages/sounddevice.py
          '';
        });
      };

      # Provider plugins to include: the same names as the optional extras in worker/pyproject.toml.
      defaultExtras = [ "openai" "deepgram" "cartesia" ];
      envFor = pkgs: extras: (pythonSetFor pkgs ./worker [ ]).mkVirtualEnv "luna-env" { luna-agent = extras; };
      listenerEnv = pkgs: (pythonSetFor pkgs ./listener [ (portaudioFix pkgs) ]).mkVirtualEnv "luna-listener-env"
        { luna-listener = [ "wakeword" ]; };
    in
    {
      packages = forAllSystems (pkgs: {
        default = envFor pkgs defaultExtras;
        listener = listenerEnv pkgs;
      });

      # Build your own worker with different provider plugins:
      #   luna.lib.envFor pkgs [ "elevenlabs" "google" ]
      lib.envFor = envFor;

      nixosModules = {
        default = import ./nix/module.nix self;            # services.luna (worker + token service)
        listener = import ./nix/listener-module.nix self;  # services.luna-listener (device endpoint)
      };

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.uv pkgs.python312 pkgs.portaudio ];
          env.UV_PYTHON_DOWNLOADS = "never";
        };
      });
    };
}
