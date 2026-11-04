self:
{ config, lib, pkgs, ... }:
let
  cfg = config.services.luna-listener;
  system = pkgs.stdenv.hostPlatform.system;
in
{
  options.services.luna-listener = {
    enable = lib.mkEnableOption "the Luna listener (wake words and calls on this device)";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${system}.listener;
      defaultText = lib.literalExpression "luna.packages.\${system}.listener";
      description = "Python environment with luna-listener and its wake-word dependencies.";
    };

    user = lib.mkOption {
      type = lib.types.str;
      example = "kiosk";
      description = ''
        The desktop user whose PipeWire session owns the microphone and speaker. The listener runs as a systemd USER
        service for this user (like an intercom), because audio devices belong to the user's session.
      '';
    };

    configFile = lib.mkOption {
      type = lib.types.path;
      default = "${self}/listener/config.yaml";
      defaultText = lib.literalExpression ''"''${luna}/listener/config.yaml"'';
      description = ''
        The listener's config.yaml (device name, token service, wake words, audio). Model files named in it are found
        relative to it, so keep any custom .onnx models next to your copy.
      '';
    };

    environmentFile = lib.mkOption {
      type = lib.types.path;
      example = "/var/lib/luna-listener/env";
      description = "File with LUNA_ACCESS_KEY=... (create one with luna-web add-device <name>). Kept out of the Nix store.";
    };

    echoCancellation = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = ''
        The listener's own echo cancellation (LiveKit's audio processing). Turn it off when the operating system already
        cancels echo, e.g. when the listener is pointed at a PipeWire echo-cancel source and sink.
      '';
    };

    environment = lib.mkOption {
      type = lib.types.attrsOf lib.types.str;
      default = { };
      example = { PIPEWIRE_ALSA = "{ capture.props = { target.object = my_mic } playback.props = { target.object = my_speaker } }"; };
      description = "Extra environment for the service. PIPEWIRE_ALSA picks the microphone and speaker (per direction) for the listener.";
    };
  };

  config = lib.mkIf cfg.enable {
    systemd.user.services.luna-listener = {
      description = "Luna listener (wake words and calls)";
      wantedBy = [ "graphical-session.target" ];
      after = [ "pipewire-pulse.service" "wireplumber.service" "network-online.target" ];
      wants = [ "pipewire-pulse.service" "wireplumber.service" ];
      unitConfig.ConditionUser = cfg.user;
      environment = {
        LUNA_LISTENER_CONFIG = toString cfg.configFile;
        LUNA_MODELS_DIR = "%S/luna-listener/models";     # built-in wake-word models, downloaded once (the package is read-only)
        PYTHONUNBUFFERED = "1";
        LUNA_ECHO_CANCELLATION = if cfg.echoCancellation then "1" else "0";
      } // cfg.environment;
      serviceConfig = {
        EnvironmentFile = cfg.environmentFile;
        StateDirectory = "luna-listener";
        ExecStartPre = "-${cfg.package}/bin/luna-listener download-models";   # "-": start anyway if offline
        ExecStart = "${cfg.package}/bin/luna-listener run";
        Restart = "always";
        RestartSec = 5;
      };
    };
  };
}
