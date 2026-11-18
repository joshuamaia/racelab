# Extra CA certificates for Docker builds (optional)

Some machines intercept HTTPS (corporate proxies, antivirus "SSL scanning"). Docker builds then fail with
`PKIX path building failed` (Maven) or `self-signed certificate in certificate chain` (npm).

Place the PEM-encoded root certificate of the interceptor here as `*.crt`. The build stages of the backend,
BFF and frontend images trust every `*.crt` in this folder. The runtime images are not affected.

`*.crt` files are git-ignored. Do not commit machine-specific certificates.
