"""Record the configured plugin signing certificate in catalog metadata."""
import hashlib
import os
import subprocess


def fingerprint():
    certificate = subprocess.check_output([
        "keytool", "-exportcert", "-keystore", os.environ["ANDROID_KEYSTORE_PATH"],
        "-alias", os.environ["ANDROID_KEY_ALIAS"], "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
    ])
    return hashlib.sha256(certificate).hexdigest()


if __name__ == "__main__":
    print(fingerprint())
