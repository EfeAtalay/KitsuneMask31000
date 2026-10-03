#!/system/bin/sh
# Attested verifiedBootHash on this image is 64 zeros. The property was empty.
DIGEST=0000000000000000000000000000000000000000000000000000000000000000
resetprop -n ro.boot.vbmeta.digest "$DIGEST"

# These boot properties are empty here. The attested state is verified and locked.
resetprop -n ro.boot.verifiedbootstate green
resetprop -n ro.boot.veritymode enforcing
resetprop -n ro.boot.warranty_bit 0

# Listed mismatches: expected encrypted and mtp. -n avoids the USB gadget restart.
resetprop -n ro.crypto.state encrypted
resetprop -n persist.sys.usb.config mtp
