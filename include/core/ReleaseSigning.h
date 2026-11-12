// cppcheck-suppress-file missingIncludeSystem
#pragma once

namespace freetunnel {

// Ed25519 public key (SubjectPublicKeyInfo PEM) used to verify SHA256SUMS.txt.sig
// in the in-app updater. The matching private key must be stored as the
// ED25519_SIGNING_KEY GitHub Actions secret (see CONTRIBUTING.md).
inline constexpr const char *kReleaseSigningPublicKeyPem =
    "-----BEGIN PUBLIC KEY-----\n"
    "MCowBQYDK2VwAyEAhE4HiZRtNxI+p9u++f1wvKCvYXa/7Zb5TlPpEyvURlE=\n"
    "-----END PUBLIC KEY-----\n";

} // namespace freetunnel
