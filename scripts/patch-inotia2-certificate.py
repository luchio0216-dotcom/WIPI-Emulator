#!/usr/bin/env python3
"""Patch the pinned WIE checkout for Inotia Chronicle 2's KTF certificate.

The original Inotia 2 archive (AID 010100D5, PID PD007974, SHA-256 prefix
974e0df9ab1e) carries a 23-byte P/cert.c2s tied to the subscriber number of
the handset that originally downloaded it. W-Feature recognises this exact
format and supplies a certificate re-sealed for the subscriber number exposed
by the emulator. Mirror that narrow compatibility behaviour here.

This patch deliberately applies only to AID 010100D5 and cert.c2s. It does not
weaken certificate handling for other titles and does not contact any server.
"""
from pathlib import Path
import os

INOTIA1_AID = "010100D3"
INOTIA1_NUMBER = "01012349876"
INOTIA2_AID = "010100D5"
INOTIA2_NUMBER = "01000000000"

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))

# patch-inotia-min.py runs first in our workflows. Extend its PHONENUMBER
# branch for Inotia 2. Also accept pristine upstream text so the script stays
# independently testable.
phone_after_inotia1 = (
    f'"PHONENUMBER" => if context.system().aid() == "{INOTIA1_AID}" '
    f'{{ "{INOTIA1_NUMBER}" }} else {{ "" }}, // Inotia KTF offline subscriber fallback'
)
phone_pristine = '"PHONENUMBER" => "", // putting this cause some game to fail authentication'
phone_replacement = (
    f'"PHONENUMBER" => if context.system().aid() == "{INOTIA1_AID}" '
    f'{{ "{INOTIA1_NUMBER}" }} else if context.system().aid() == "{INOTIA2_AID}" '
    f'{{ "{INOTIA2_NUMBER}" }} else {{ "" }}, // Inotia KTF subscriber compatibility'
)

certificate_code = r'''
// Inotia Chronicle 2 (010100D5) packages a 23-byte publisher certificate in
// P/cert.c2s. The first 20 bytes are XOR-sealed plaintext (8-byte AID followed
// by the subscriber number in a 12-byte NUL-terminated field); the last three
// bytes are ciphertext checksum, salt, and plaintext checksum. This is the
// exact format used by the title and mirrored by W-Feature's KTF compatibility.
const INOTIA2_AID: &str = "010100D5";
const INOTIA2_CERT_NAME: &str = "cert.c2s";
const INOTIA2_SUBSCRIBER: &[u8; 11] = b"01000000000";
const INOTIA2_CERT_TABLE: [u8; 256] = [
    0x8c, 0x0d, 0xae, 0x4f, 0xe8, 0x81, 0xd2, 0x53, 0x10, 0x35, 0xd6, 0x77, 0x64, 0xa5, 0x96, 0x2b,
    0x34, 0x1d, 0x9e, 0x5f, 0x60, 0x81, 0xbe, 0x73, 0xe4, 0x35, 0xa2, 0x87, 0x28, 0x69, 0x2a, 0x9b,
    0x78, 0xfd, 0x4e, 0xbf, 0x30, 0x51, 0x8e, 0xe3, 0x74, 0xf5, 0x32, 0xa7, 0xf8, 0xc9, 0xb6, 0x9b,
    0x3c, 0x7d, 0x3e, 0x5f, 0x30, 0x01, 0xde, 0x23, 0x04, 0x85, 0x56, 0x57, 0x48, 0x69, 0xaa, 0x4b,
    0x5c, 0xcd, 0xaa, 0x6d, 0xb2, 0x2b, 0x08, 0xe5, 0x82, 0x57, 0xe0, 0xef, 0xee, 0x1b, 0x34, 0x9d,
    0x62, 0x7b, 0x2e, 0x85, 0x5e, 0xbf, 0x34, 0x0d, 0x7a, 0x61, 0x94, 0xb9, 0xa2, 0x9f, 0x7a, 0xcf,
    0x3a, 0x4f, 0x88, 0xa9, 0xe6, 0x8f, 0xb2, 0x79, 0xb6, 0x2f, 0x78, 0xfd, 0x76, 0xbb, 0x48, 0xcb,
    0xca, 0xff, 0x18, 0xb5, 0x80, 0xe7, 0x50, 0x05, 0x6e, 0x5b, 0x68, 0x5d, 0x06, 0xa9, 0x30, 0xc9,
    0xce, 0x93, 0x20, 0x99, 0xd8, 0x6f, 0xa0, 0x91, 0xe6, 0x3b, 0x94, 0xe3, 0x46, 0xf7, 0xdc, 0x2d,
    0x94, 0xfb, 0xac, 0xa1, 0xe6, 0x43, 0x08, 0x23, 0x68, 0xb3, 0x88, 0xc1, 0xde, 0x2b, 0xd0, 0xeb,
    0xd2, 0xab, 0x0c, 0x7d, 0x32, 0x03, 0x3c, 0x8d, 0xfe, 0xeb, 0xf6, 0x65, 0x16, 0xd7, 0x88, 0xed,
    0xea, 0xbd, 0x2c, 0xe1, 0xda, 0xb7, 0xd0, 0xf1, 0x16, 0xab, 0xe4, 0x15, 0x8a, 0x57, 0x16, 0x17,
    0x4a, 0x6b, 0x6c, 0x9d, 0x7e, 0x1f, 0xae, 0xa1, 0x72, 0x37, 0x20, 0xef, 0xee, 0x9b, 0xf4, 0x11,
    0xec, 0x03, 0xec, 0x81, 0x66, 0x03, 0x30, 0x15, 0x6a, 0x65, 0xea, 0x35, 0xc6, 0x07, 0x38, 0x8d,
    0x3e, 0x75, 0x26, 0x6d, 0x9e, 0x53, 0x44, 0x79, 0x12, 0xf1, 0xa4, 0x45, 0xb6, 0x47, 0xf8, 0x29,
    0xae, 0xab, 0x7c, 0x2d, 0x72, 0x2f, 0x12, 0x51, 0x86, 0x9d, 0x3a, 0x03, 0x64, 0xe9, 0xe2, 0xdb,
];

fn inotia2_checksum(data: &[u8]) -> u8 {
    data.iter().fold(0u8, |sum, value| sum.wrapping_add(*value))
}

fn provision_inotia2_certificate(original: &[u8]) -> Option<Vec<u8>> {
    const BODY_LEN: usize = 20;
    const CERT_LEN: usize = 23;
    if original.len() != CERT_LEN || inotia2_checksum(&original[..BODY_LEN]) != original[BODY_LEN] {
        return None;
    }

    let salt = original[BODY_LEN + 1];
    let mut plain = [0u8; BODY_LEN];
    plain[..8].copy_from_slice(INOTIA2_AID.as_bytes());
    plain[8..19].copy_from_slice(INOTIA2_SUBSCRIBER);

    let mut result = Vec::with_capacity(CERT_LEN);
    for (index, value) in plain.iter().enumerate() {
        let counter = index + salt as usize;
        let key = INOTIA2_CERT_TABLE[counter & 0xff]
            .wrapping_add(INOTIA2_SUBSCRIBER[counter % INOTIA2_SUBSCRIBER.len()]);
        result.push(*value ^ key);
    }
    result.push(inotia2_checksum(&result));
    result.push(salt);
    result.push(inotia2_checksum(&plain));
    Some(result)
}
'''

certificate_insert_needle = 'const MAX_NAME_LEN: usize = 31; // leave a byte for null terminator inside the 32-byte field\n'
open_needle = '    let packaged = read_packaged_database(context, &name).await?;\n\n    let system = context.system();\n    let pid = system.pid().to_owned();\n    let exists = system.platform().database_repository().exists(&name, &pid).await;\n'
open_replacement = '''    let inotia2_certificate = context.system().aid() == INOTIA2_AID && name == INOTIA2_CERT_NAME;
    let mut packaged = read_packaged_database(context, &name).await?;
    if inotia2_certificate {
        let provisioned = packaged.as_deref().and_then(provision_inotia2_certificate);
        if let Some(data) = provisioned {
            tracing::debug!("Inotia 2: provisioned 23-byte cert.c2s for subscriber 01000000000");
            packaged = Some(data);
        }
    }

    let system = context.system();
    let pid = system.pid().to_owned();
    let exists = system.platform().database_repository().exists(&name, &pid).await;

    // A previous failed start may have persisted or deleted the handset-bound
    // certificate. Always refresh this one recognized title's record from the
    // provisioned view so an APK upgrade can recover without re-importing it.
    if inotia2_certificate {
        if let Some(data) = packaged.as_ref() {
            let mut db = system.platform().database_repository().open(&name, &pid).await;
            db.set(1, data).await;
        }
    }
'''

patched = []
already = []
for root in roots:
    kernel_paths = list(root.glob("**/wie_wipi_c/src/api/kernel.rs"))
    database_paths = list(root.glob("**/wie_wipi_c/src/api/database.rs"))

    for path in kernel_paths:
        text = path.read_text()
        if phone_replacement in text:
            already.append(path)
            continue
        if phone_after_inotia1 in text:
            text = text.replace(phone_after_inotia1, phone_replacement, 1)
        elif phone_pristine in text:
            # Standalone use: preserve the Inotia 1 branch as well.
            text = text.replace(phone_pristine, phone_replacement, 1)
        else:
            continue
        path.write_text(text)
        patched.append(path)

    for path in database_paths:
        text = path.read_text()
        changed = False
        if certificate_code not in text:
            if certificate_insert_needle not in text:
                continue
            text = text.replace(certificate_insert_needle, certificate_insert_needle + certificate_code + "\n", 1)
            changed = True
        if open_replacement not in text:
            if open_needle not in text:
                continue
            text = text.replace(open_needle, open_replacement + "\n", 1)
            changed = True
        if changed:
            path.write_text(text)
            patched.append(path)
        else:
            already.append(path)

if not patched and not already:
    raise SystemExit("Could not find the pinned WIE targets for Inotia 2 certificate compatibility")

for path in patched:
    print(f"inotia2 certificate patched: {path}")
for path in already:
    print(f"inotia2 certificate already patched: {path}")
