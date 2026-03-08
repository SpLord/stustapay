{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }: flake-utils.lib.eachDefaultSystem (system:
    let
      pkgs = import nixpkgs {
        inherit system;
        overlays = [];
        config = {
          # This exception is needed for a stustapay dependency.
          # It's irrelevant for security in production because it's used exclusively in the TSE simulator.
          permittedInsecurePackages = [
            "python3.13-ecdsa-0.19.1"
          ];
        };
      };
      python = pkgs.python3.override {
        self = python;
        packageOverrides = final: prev: {
          sftkit = final.buildPythonPackage rec {
            pname = "sftkit";
            version = "0.4.2";
            src = prev.fetchPypi {
              inherit pname version;
              hash = "sha256-dj+rV69lU7LzJaNGTsi0wTfg1tyNxI7J8BKf3iUQfYw=";
            };
            pyproject = true;
            doCheck = false;
            build-system = with final; [
              uv-build
            ];
            dependencies = with final; [
              fastapi
              typer
              uvicorn
              asyncpg
              pydantic
            ];
            pythonRelaxDeps = [ "pydantic" ];
            postPatch = ''
              substituteInPlace pyproject.toml \
                --replace "uv_build>=0.9.9,<0.10.0" "uv_build>=0.9.7,<0.10.0"
            '';
          };
        };
      };
    in with pkgs; {
      packages.default = self.packages.${system}.stustapay;

      packages.stustapay-admin-ui = pkgs.buildNpmPackage {
        pname = "stustapay-admin-ui";
        version = "0.1.0";
        src = ./web;
        npmDepsHash = "sha256-e+SfMEuub7YSCY0fKpgL51pT6SQwn57Y6J+SPx/uLK4=";
        npmInstallFlags = "--verbose";
        dontNpmBuild = true;
        buildPhase = ''
          ${pkgs.util-linux}/bin/script -c "npx nx --verbose build administration" /dev/null
        '';
        dontNpmInstall = true;
        installPhase = ''
          mkdir -p $out
          mv dist/apps/administration/* $out/.
        '';
        CYPRESS_INSTALL_BINARY = 0;
        CYPRESS_RUN_BINARY = "${pkgs.cypress}/bin/Cypress";
      };

      packages.stustapay-customer-ui = pkgs.buildNpmPackage {
        pname = "stustapay-customer-ui";
        version = "0.1.0";
        src = ./web;
        npmDepsHash = "sha256-e+SfMEuub7YSCY0fKpgL51pT6SQwn57Y6J+SPx/uLK4=";
        npmInstallFlags = "--verbose";
        dontNpmBuild = true;
        buildPhase = ''
          ${pkgs.util-linux}/bin/script -c "npx nx --verbose build customerportal" /dev/null
        '';
        dontNpmInstall = true;
        installPhase = ''
          mkdir -p $out
          mv dist/apps/customerportal/* $out/.
        '';
        CYPRESS_INSTALL_BINARY = 0;
        CYPRESS_RUN_BINARY = "${pkgs.cypress}/bin/Cypress";
      };

      packages.stustapay = with python.pkgs; buildPythonPackage {
        pname = "stustapay";
        version = "0.1.0";
        src = ./.;
        pyproject = true;
        build-system = [
          setuptools
        ];
        dependencies = [
          sftkit
          fastapi
          typer
          uvicorn
          asyncpg
          pydantic
          python-jose
          jinja2
          aiohttp
          pylatexenc
          schwifty
          sepaxml
          asn1crypto
          ecdsa
          dateutils
          aiosmtplib
          bcrypt
          passlib
          pyyaml
          email-validator
          python-multipart
          weasyprint
          mako
          pandas
          websockets
        ];
        pythonRelaxDeps = [
          "jinja2"
          "aiohttp"
          "schwifty"
          "ecdsa"
          "aiosmtplib"
          "bcrypt"
          "pyyaml"
          "fastapi"
          "typer"
          "uvicorn"
          "pydantic"
          "python-multipart"
          "python-jose"
          "sepaxml"
          "passlib"
          "weasyprint"
          "mako"
          "pandas"
        ];
      };

      packages.sftkit = python.pkgs.sftkit;

      devShell = mkShell rec {
        buildInputs = [
          (python3.withPackages(ps: with ps; [
            pip
          ]))
          nodejs
          typst
        ];
      };
    }
  );
}
