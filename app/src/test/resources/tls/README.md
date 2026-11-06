# Test certificates

These PKCS12 files contain public test keys only, with password `test-only`. They are used by JVM loopback mTLS tests, never packaged as application assets or used for production identities. The app generates its own private key in AndroidKeyStore.
