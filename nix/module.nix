self:
{ config, lib, pkgs, ... }:
let
  cfg = config.services.luna;
  system = pkgs.stdenv.hostPlatform.system;
in
{
  options.services.luna = {
    enable = lib.mkEnableOption "the Luna voice assistant agent (LiveKit Agents)";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${system}.default;
      defaultText = lib.literalExpression "luna.packages.\${system}.default";
      description = ''
        Python environment containing luna-agent and its provider plugins. To use other
        providers: `luna.lib.envFor pkgs [ "elevenlabs" "google" ]`.
      '';
    };

    configFile = lib.mkOption {
      type = lib.types.path;
      default = "${self}/worker/config.yaml";
      defaultText = lib.literalExpression ''"''${luna}/worker/config.yaml"'';
      description = "The config.yaml (providers, devices, TV, caregivers, turn-taking). Keep your own copy in your repo and point this at it.";
    };

    environmentFile = lib.mkOption {
      type = lib.types.path;
      example = "/var/lib/luna/env";
      description = ''
        File with the secrets, one KEY=value per line, kept OUT of the Nix store:
        LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET, HA_URL, HA_TOKEN, LUNA_ACCESS_KEYS,
        and the provider keys config.yaml needs (DEEPGRAM_API_KEY, ...).
        See .env.example in the repository.
      '';
    };

    agentPort = lib.mkOption {
      type = lib.types.port;
      default = 8081;
      description = "Port of the worker's health/metrics server (LiveKit's default is 8081; change it if something else uses it).";
    };

    dryRun = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = "Read Home Assistant but never switch anything or send notifications. Turn off when you are ready for it to act.";
    };

    web = {
      enable = lib.mkEnableOption "the token endpoint and web UI (needed for the browser and Android clients)";
      host = lib.mkOption { type = lib.types.str; default = "0.0.0.0"; };
      port = lib.mkOption { type = lib.types.port; default = 8080; };
      openFirewall = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Open the web port. Browsers only allow the microphone on https:// or localhost: put a TLS proxy in front for devices on the network.";
      };
    };
  };

  config = lib.mkIf cfg.enable {
    users.users.luna = {
      isSystemUser = true;
      group = "luna";
      home = "/var/lib/luna";
    };
    users.groups.luna = { };

    systemd.services =
      let
        common = {
          after = [ "network-online.target" ];
          wants = [ "network-online.target" ];
          wantedBy = [ "multi-user.target" ];
          environment = {
            LUNA_CONFIG = toString cfg.configFile;
            LUNA_WEB_DIR = "${self}/web";   # the browser UI
            LUNA_STATE_DIR = "/var/lib/luna/state";
            LUNA_AUDIT_LOG = "/var/lib/luna/logs/audit.jsonl";
            LUNA_DRY_RUN = if cfg.dryRun then "1" else "0";
            LUNA_AGENT_PORT = toString cfg.agentPort;
            HF_HOME = "/var/lib/luna/hf"; # model weights downloaded once, cached here
            HOME = "/var/lib/luna";
          };
          serviceConfig = {
            User = "luna";
            Group = "luna";
            EnvironmentFile = cfg.environmentFile;
            StateDirectory = "luna";
            Restart = "always";
            RestartSec = 5;
            # Hardening: it only needs the network and its own state directory.
            NoNewPrivileges = true;
            ProtectSystem = "strict";
            ProtectHome = true;
            PrivateTmp = true;
            PrivateDevices = true;
            ProtectKernelTunables = true;
            ProtectKernelModules = true;
            ProtectControlGroups = true;
            RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
            LockPersonality = true;
          };
        };
      in
      {
        luna-agent = lib.recursiveUpdate common {
          description = "Luna voice assistant (LiveKit agent worker)";
          serviceConfig = {
            # The local turn-detector / VAD weights are fetched once; "-" lets it start offline afterwards.
            ExecStartPre = "-${cfg.package}/bin/python -m livekit.agents download-files";
            ExecStart = "${cfg.package}/bin/python -m luna.agent start";
            TimeoutStopSec = "15min"; # let a running conversation finish
          };
        };
      } // lib.optionalAttrs cfg.web.enable {
        luna-web = lib.recursiveUpdate common {
          description = "Luna token endpoint and web UI";
          environment = {
            LUNA_WEB_HOST = cfg.web.host;
            LUNA_WEB_PORT = toString cfg.web.port;
          };
          serviceConfig.ExecStart = "${cfg.package}/bin/python -m luna.web";
        };
      };

    networking.firewall.allowedTCPPorts = lib.mkIf (cfg.web.enable && cfg.web.openFirewall) [ cfg.web.port ];
  };
}
