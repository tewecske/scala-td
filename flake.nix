{
  description = "Square Defense: dev shells and tools, pinned by flake.lock";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # What the game needs at runtime: libGDX/LWJGL load OpenGL and the X11
      # libraries with dlopen, so they must be on LD_LIBRARY_PATH.
      runtimeLibs = pkgs: with pkgs; [
        libGL libx11 libxext libxcursor libxrandr libxi libxinerama libxxf86vm libxrender libxfixes
      ];

      jdk = pkgs: pkgs.jdk21;
      sbt = pkgs: pkgs.sbt.override { jre = jdk pkgs; };

      # Mesa's software OpenGL, for machines (or CI) without a GPU or display.
      softwareGl = pkgs: {
        LIBGL_DRIVERS_PATH = "${pkgs.mesa}/lib/dri";
        __EGL_VENDOR_LIBRARY_DIRS = "${pkgs.mesa}/share/glvnd/egl_vendor.d";
        __GLX_VENDOR_LIBRARY_NAME = "mesa";
        LIBGL_ALWAYS_SOFTWARE = "1";
        GALLIUM_DRIVER = "llvmpipe";
      };

      # Screenshot tour on a virtual display: every screen saved as a PNG.
      shots = pkgs: pkgs.writeShellApplication {
        name = "td-shots";
        runtimeInputs = [ (sbt pkgs) (jdk pkgs) pkgs.xvfb-run pkgs.xauth ];
        text = ''
          # usage: nix run .#shots -- [WIDTHxHEIGHT] [output dir]
          res="''${1:-1920x1080}"
          out="$(realpath -m "''${2:-data/screenshots/tour-$res}")"
          export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath (runtimeLibs pkgs ++ [ pkgs.mesa ])}"
          ${pkgs.lib.concatStringsSep "\n" (pkgs.lib.mapAttrsToList (k: v: "export ${k}=${pkgs.lib.escapeShellArg v}") (softwareGl pkgs))}
          export TD_TOUR="$out" TD_WINDOW="$res"
          echo "screenshot tour at $res -> $out"
          xvfb-run -a -s "-screen 0 3840x2160x24 +extension GLX" sbt -batch -no-colors game/run
        '';
      };
    in {
      devShells = forAll (pkgs: {
        # nix develop: JDK + sbt, and the libraries to run the game window
        default = pkgs.mkShell {
          packages = [ (jdk pkgs) (sbt pkgs) pkgs.git ];
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (runtimeLibs pkgs);
          JAVA_HOME = "${jdk pkgs}";
        };

        # nix develop .#headless: the default shell plus a virtual display with
        # software OpenGL, for running the GUI where there is no screen
        headless = pkgs.mkShell ({
          packages = [ (jdk pkgs) (sbt pkgs) pkgs.git pkgs.xvfb-run pkgs.xauth (shots pkgs) ];
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (runtimeLibs pkgs ++ [ pkgs.mesa ]);
          JAVA_HOME = "${jdk pkgs}";
        } // softwareGl pkgs);
      });

      apps = forAll (pkgs: {
        shots = {
          type = "app";
          program = "${shots pkgs}/bin/td-shots";
          meta.description = "Screenshot every screen of the game on a virtual display";
        };
      });
    };
}
