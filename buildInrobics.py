import sys
import os
import subprocess
import re
import time
import json
import getpass
from datetime import datetime

# ==============================================================================
# FLAG DE ASSET PACKS (Play Asset Delivery)
# Si te fijas que la instalación es MUY lenta (Pushed "/sdcard/...") es porque
# --local-testing fuerza a copiar archivos grandes de uno en uno a la tablet.
# Si tus assets de Unity (audio_assets, UnityDataAssetPackCare) son asincronos
# ("on-demand" o "fast-follow") necesitas dejar esto en True para que funcionen
# sin descargarlos de PlayStore. Si son "install-time" (vienen de base), ponlo
# en False y el streaming será 3-5 veces más rápido al instalar de forma nativa.
# ==============================================================================
USE_LOCAL_TESTING = False

SIGNING_CREDENTIALS_FILE = ".signing_credentials.json"
KEYSTORE_MAP = {
    "care":    "upload-keystore-care.jks",
    "virtual": "upload-keystore-virtual.jks",
    "clinic":  "upload-keystore2.jks",
}

def get_gradle_command():
    return "./gradlew" if os.name != 'nt' else "gradlew.bat"

def check_device_connected():
    try:
        result = subprocess.check_output(["adb", "devices"]).decode("utf-8")
        lines = [line for line in result.split('\n') if line.strip() and not line.startswith('List') and 'device' in line]
        if len(lines) > 0:
            device_id = lines[0].split('\t')[0]
            try:
                model = subprocess.check_output(["adb", "-s", device_id, "shell", "getprop", "ro.product.model"]).decode("utf-8").strip()
                return model
            except:
                return "Dispositivo desconocido"
        return None
    except:
        return None

def get_package_name(flavor):
    """Intenta extraer el applicationId del build.gradle según el flavor para abrir la app luego."""
    if not flavor:
        return "com.inrobics.app" # default clinic
    try:
        flavor_lower = flavor.lower()
        if flavor_lower == "virtual":
            return "com.inrobics.virtual"
        elif flavor_lower == "care":
            return "com.inrobics.care"
        elif flavor_lower == "educa":
            return "com.inrobics.educa"
        else:
            return "com.inrobics.app"
    except:
        return "com.inrobics.app"

def parse_args():
    run_validators_only = "--run_validators" in sys.argv
    if run_validators_only:
        return None, None, None, False, True

    if len(sys.argv) < 3:
        print("Uso: ./buildInrobics <Flavor> <BuildType> [APK/Bundle] [--install-only]")
        sys.exit(1)

    install_only = "--install-only" in sys.argv
    args_clean = [arg for arg in sys.argv if arg != "--install-only"]

    flavor = args_clean[1].lower()
    build_type = args_clean[2].capitalize()
    output_format = "Bundle"
    if len(args_clean) >= 4:
        output_format = "APK" if args_clean[3].upper() == "APK" else "Bundle"

    return flavor, build_type, output_format, install_only, False

def update_version():
    version_file = "inrobics/src/main/AndroidManifest.xml"
    if not os.path.exists(version_file): return None
    with open(version_file, 'r', encoding='utf-8') as f: content = f.read()

    v_code_match = re.search(r'android:versionCode="(\d+)"', content)
    v_name_match = re.search(r'android:versionName="([^"]+)"', content)

    if not v_code_match or not v_name_match: return None

    current_code = v_code_match.group(1)
    current_name = v_name_match.group(1)

    now = datetime.now()
    today = now.strftime("%y%m%d")
    new_code = today + ("0" if not current_code.startswith(today) else str(int(current_code[-1]) + 1))
    new_name = now.strftime("%Y.%m.%d") + "-" + (current_name.split('-')[1] if '-' in current_name else "01")

    print(f"\n--- Versión: {current_name} (code {current_code}) -> {new_name} (code {new_code}) ---")
    val = input("¿Incrementar versión? (Y/n): ").lower()
    if val in ['', 'y', 'yes']:
        content = content.replace(f'android:versionCode="{current_code}"', f'android:versionCode="{new_code}"')
        content = content.replace(f'android:versionName="{current_name}"', f'android:versionName="{new_name}"')
        with open(version_file, 'w', encoding='utf-8') as f: f.write(content)
        print("Versión actualizada.")
        return new_name
    return current_name

ASSETS_SRC = os.path.join("inrobics", "src", "main", "assets")
# Directorio del módulo Gradle asset pack (debe coincidir con el módulo en settings.gradle)
ASSETS_MEDIAPIPE = os.path.join("mediapipe_assets", "src", "main", "assets")
# Solo se mueven archivos con estas extensiones (modelos MediaPipe)
MEDIAPIPE_EXTENSIONS = (".task",)

def move_assets_to_mediapipe():
    """Mueve SOLO los modelos MediaPipe (.task) de inrobics/src/main/assets a mediapipe_assets/src/main/assets."""
    import shutil
    if not os.path.isdir(ASSETS_SRC):
        print(f"⚠️  No existe {ASSETS_SRC}, nada que mover.")
        return
    os.makedirs(ASSETS_MEDIAPIPE, exist_ok=True)
    moved = []
    for item in os.listdir(ASSETS_SRC):
        if not any(item.endswith(ext) for ext in MEDIAPIPE_EXTENSIONS):
            continue  # Solo mover archivos .task (modelos MediaPipe)
        src_path = os.path.join(ASSETS_SRC, item)
        dst_path = os.path.join(ASSETS_MEDIAPIPE, item)
        shutil.move(src_path, dst_path)
        moved.append(item)
    if moved:
        print(f"📂 Modelos MediaPipe movidos a mediapipe_assets ({len(moved)} elementos): {', '.join(moved)}")
    else:
        print(f"ℹ️  No se encontraron modelos .task en {ASSETS_SRC}")

def restore_assets_from_mediapipe():
    """Restaura los modelos MediaPipe de mediapipe_assets/src/main/assets a inrobics/src/main/assets."""
    import shutil
    if not os.path.isdir(ASSETS_MEDIAPIPE):
        return
    os.makedirs(ASSETS_SRC, exist_ok=True)
    restored = []
    for item in os.listdir(ASSETS_MEDIAPIPE):
        src_path = os.path.join(ASSETS_MEDIAPIPE, item)
        dst_path = os.path.join(ASSETS_SRC, item)
        shutil.move(src_path, dst_path)
        restored.append(item)
    if restored:
        print(f"📂 Modelos MediaPipe restaurados a inrobics/src/main/assets ({len(restored)} elementos).")


def install_bundle(flavor, build_type, skip_build_apks=False, device_name="Unknown"):
    import urllib.request
    
    # Descargar bundletool si no existe
    bundletool_path = "tools/bundletool.jar"
    if not os.path.exists("tools"):
        os.makedirs("tools")
    if not os.path.exists(bundletool_path):
        print("📥 Descargando bundletool.jar para calcular e instalar App Bundle directamente...")
        url = "https://github.com/google/bundletool/releases/download/1.16.0/bundletool-all-1.16.0.jar"
        urllib.request.urlretrieve(url, bundletool_path)
    
    # Encontrar el AAB generado
    aab_dir = f"inrobics/build/outputs/bundle/{flavor}{build_type}"
    apks_path = f"inrobics/build/outputs/bundle/app.apks"
    
    aab_path = None
    if os.path.exists("inrobics/build/outputs/bundle"):
        for root, dirs, files in os.walk("inrobics/build/outputs/bundle"):
            for f in files:
                if f.endswith(".aab"):
                    aab_path = os.path.join(root, f)
                    break
            if aab_path:
                break
    
    if not aab_path:
        print("❌ No se encontró el archivo .aab (App Bundle) en inrobics/build/outputs/bundle/")
        return False
        
    if not skip_build_apks or not os.path.exists(apks_path):
        print(f"📦 Convirtiendo App Bundle ({aab_path}) a APKs para uso local (Split APK)...")
        if os.path.exists(apks_path):
            os.remove(apks_path)
            
        local_testing_flag = "--local-testing" if USE_LOCAL_TESTING else ""
        if os.system(f"java -Xmx8G -jar {bundletool_path} build-apks --bundle={aab_path} --output={apks_path} {local_testing_flag} --connected-device") != 0:
            return False
        
    print(f"📲 Desplegando el App Bundle sobre la tablet ({device_name})...")
    start_time = time.time()
    
    # Redirigir el directorio temporal de Java al propio build para evitar escaneos lentos de Windows Defender
    # en la carpeta Temp de AppData.
    temp_dir = "inrobics/build/outputs/bundle/temp"
    os.makedirs(temp_dir, exist_ok=True)
    if os.system(f"java -Djava.io.tmpdir={temp_dir} -Xmx8G -jar {bundletool_path} install-apks --apks={apks_path}") != 0:
        return False
    end_time = time.time()
    print(f"⏱️ Tiempo de subida e instalación: {end_time - start_time:.2f} segundos.")
        
    return True

def get_signing_credentials(flavor):
    """Devuelve credenciales de firma para release. Las pide la primera vez y las cachea."""
    if flavor not in KEYSTORE_MAP:
        return None
    creds_all = {}
    if os.path.exists(SIGNING_CREDENTIALS_FILE):
        with open(SIGNING_CREDENTIALS_FILE, 'r') as f:
            creds_all = json.load(f)
    if flavor in creds_all:
        print(f"🔑 Usando credenciales guardadas para '{flavor}'.")
        return creds_all[flavor]
    keystore_file = KEYSTORE_MAP[flavor]
    print(f"\n🔑 Credenciales de firma para '{flavor}' (keystore: {keystore_file}):")
    key_alias = input("  Key alias: ").strip()
    keystore_password = getpass.getpass("  Keystore password: ")
    key_password = getpass.getpass("  Key password: ")
    flavor_creds = {
        "keystore_file": keystore_file,
        "key_alias": key_alias,
        "keystore_password": keystore_password,
        "key_password": key_password,
    }
    creds_all[flavor] = flavor_creds
    with open(SIGNING_CREDENTIALS_FILE, 'w') as f:
        json.dump(creds_all, f, indent=2)
    print(f"💾 Credenciales guardadas en {SIGNING_CREDENTIALS_FILE}")
    return flavor_creds


def write_keystore_properties(signing_creds):
    """Escribe keystore.properties (ignorado por git) para que Gradle firme el release."""
    lines = [
        f"KEYSTORE_FILE={signing_creds['keystore_file']}",
        f"KEYSTORE_PASSWORD={signing_creds['keystore_password']}",
        f"KEY_ALIAS={signing_creds['key_alias']}",
        f"KEY_PASSWORD={signing_creds['key_password']}",
    ]
    with open("keystore.properties", 'w') as f:
        f.write("\n".join(lines) + "\n")


def print_output_path(flavor, build_type, output_format):
    """Imprime la ruta del artefacto generado."""
    if output_format == "Bundle":
        out_dir = os.path.join("inrobics", "build", "outputs", "bundle", f"{flavor}{build_type}")
        ext = ".aab"
    else:
        out_dir = os.path.join("inrobics", "build", "outputs", "apk", flavor, build_type.lower())
        ext = ".apk"
    if os.path.exists(out_dir):
        files = [f for f in os.listdir(out_dir) if f.endswith(ext)]
        if files:
            latest = max(files, key=lambda f: os.path.getmtime(os.path.join(out_dir, f)))
            print(f"📁 Artefacto: {os.path.abspath(os.path.join(out_dir, latest))}")
            return
    print(f"📁 Directorio de salida: {os.path.abspath(out_dir)}")


def run_validators(blocking):
    """
    Ejecuta los validadores de XML de strings y speeches.
    Si blocking=True (release): aborta si alguno falla.
    Si blocking=False (debug): imprime warnings al final.
    Devuelve True si todos pasan, False si alguno falla.
    """
    validators = [
        ("Strings XML",  [sys.executable, "admindb/Strings/string_xml_validator.py",  "--dir", "inrobics/src/main/res/"]),
        ("Speeches XML", [sys.executable, "admindb/Speeches/speech_xml_validator.py", "--dir", "inrobics/src/main/assets/speeches/"]),
    ]
    failures = []
    for name, cmd in validators:
        print(f"\n🔍 Validador: {name}...")
        res = subprocess.run(cmd)
        if res.returncode != 0:
            failures.append(name)
    if failures:
        if blocking:
            print(f"\n❌ Validación fallida: {', '.join(failures)}")
            print("   Corrige los errores antes de compilar en Release.")
        else:
            print(f"\n⚠️  WARNINGS de validación (build completada igualmente):")
            for name in failures:
                print(f"   ⚠️  {name}: validación fallida")
        return False
    print("\n✅ Validaciones correctas.")
    return True


def manage_vosk_assets(flavor):
    import zipfile
    import shutil

    zip_path = "vosk-model-small-es-0.42.zip"
    model_name = "vosk-model-small-es-0.42"
    dest_dir = os.path.join("inrobics", "src", "main", "assets")
    extracted_path = os.path.join(dest_dir, model_name)

    if flavor.lower() == "care":
        if os.path.isdir(extracted_path):
            print(f"✅ Vosk model ya existe en {extracted_path}, no se descomprime de nuevo.")
        else:
            if not os.path.exists(zip_path):
                print(f"❌ No se encontró {zip_path}. Asegúrate de que esté en la raíz del proyecto.")
                return
            print(f"📦 Descomprimiendo {zip_path} en {dest_dir}...")
            os.makedirs(dest_dir, exist_ok=True)
            with zipfile.ZipFile(zip_path, 'r') as z:
                z.extractall(dest_dir)
            print(f"✅ Vosk model descomprimido en {extracted_path}")
    else:
        if os.path.isdir(extracted_path):
            print(f"🗑️ Flavor={flavor}: eliminando carpeta Vosk {extracted_path}...")
            shutil.rmtree(extracted_path)
            print("✅ Carpeta Vosk eliminada.")
        else:
            print(f"ℹ️ Flavor={flavor}: carpeta Vosk no existe, nada que eliminar.")


def main():
    flavor, build_type, output_format, install_only, run_validators_only = parse_args()

    if run_validators_only:
        success = run_validators(blocking=False)
        sys.exit(0 if success else 1)

    manage_vosk_assets(flavor)
    gradle_cmd = get_gradle_command()
    device_name = check_device_connected()
    device_ready = device_name is not None

    if build_type == "Release" and not install_only:
        sign_answer = input("¿Firmar con keystore para Play Store? (s/n): ").strip().lower()
        sign_build = sign_answer in ['s', 'si', 'y', 'yes']
        if sign_build:
            version_name = update_version()
            signing_creds = get_signing_credentials(flavor)
            if signing_creds:
                write_keystore_properties(signing_creds)
                if version_name:
                    print(f"\n🏷️  Versión a subir: {version_name}")
        else:
            # Eliminar keystore.properties para que Gradle use signingConfigs.debug
            if os.path.exists("keystore.properties"):
                os.remove("keystore.properties")
            print("ℹ️ Build sin firma de producción (debug keystore).")
        if not run_validators(blocking=True) and sign_build:
            sys.exit(1)

    # Si hay tablet, usamos 'install'. Gradle se encarga de procesar el Bundle si es necesario.
    if device_ready:
        if output_format == "Bundle":
            if install_only:
                print(f"📦 Saltando compilación. Instalando {output_format}...")
                res_code = 0
            else:
                print(f"📦 Usando BundleTool para compilar e instalar el {output_format}...")
                # Optimizaciones de compilación: cache, workers, omitir test/lint
                build_cmd = f"{gradle_cmd} bundle{flavor.capitalize()}{build_type} -x lint -x test --parallel --build-cache"
                print(f"🚀 Ejecutando: {build_cmd}")
                # TODO: this seems to affect performance, for now it will be commented: 
                move_assets_to_mediapipe()
                try:
                    res = subprocess.run(build_cmd, shell=True)
                    res_code = res.returncode
                finally:
                    pass
                    restore_assets_from_mediapipe()
                
            if res_code == 0:
                while True:
                    success = install_bundle(flavor.lower(), build_type.capitalize(), skip_build_apks=install_only, device_name=device_name)
                    if success:
                        print_output_path(flavor, build_type, "Bundle")
                        if build_type != "Release":
                            run_validators(blocking=False)
                        pkg = get_package_name(flavor)
                        if pkg:
                            print(f"✅ Instalado. Abriendo {pkg}...")
                            subprocess.run(f"adb shell monkey -p {pkg} -c android.intent.category.LAUNCHER 1", shell=True, stdout=subprocess.DEVNULL)
                        break
                    else:
                        print(f"❌ Error al instalar el App Bundle.")
                        retry = input("¿Reintentar solo la instalación? (s/n): ").lower()
                        if retry not in ['s', 'si', 'y', 'yes']:
                            break
                        install_only = True # En el reintento ya no compilamos
            else:
                print(f"❌ Error en la build del Bundle.")
            return
        else:
            print(f"📱 Tablet detectada ({device_name}). Instalando {output_format}...")
            task = f"install{flavor.capitalize()}{build_type}"
    else:
        print(f"📦 No hay tablet. Generando {output_format} solamente...")
        task = f"assemble{flavor.capitalize()}{build_type}" if output_format == "APK" else f"bundle{flavor.capitalize()}{build_type}"

    if not (device_ready and install_only and output_format == "APK"):
        # Optimizaciones de compilación también para la generación convencional
        full_cmd = f"{gradle_cmd} {task} -x lint -x test --parallel --build-cache"
        print(f"🚀 Ejecutando: {full_cmd}")
        res = subprocess.run(full_cmd, shell=True)
        res_code = res.returncode
    else:
        res_code = 0
        print("📦 Saltando compilación. (Para APK usa gradle install o interactúa con adb directamente)")

    if res_code == 0 and device_ready and output_format == "APK":
        print_output_path(flavor, build_type, "APK")
        if build_type != "Release":
            run_validators(blocking=False)
        pkg = get_package_name(flavor)
        if pkg:
            print(f"✅ Instalado. Abriendo {pkg}...")
            subprocess.run(f"adb shell monkey -p {pkg} -c android.intent.category.LAUNCHER 1", shell=True, stdout=subprocess.DEVNULL)
    elif res_code == 0:
        print_output_path(flavor, build_type, output_format)
        if build_type != "Release":
            run_validators(blocking=False)
        print("\n✅ Build completada exitosamente.")
    else:
        print("\n❌ Error en la build.")

if __name__ == "__main__":
    main()