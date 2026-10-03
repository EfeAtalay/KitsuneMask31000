#!/system/bin/sh
# The attested verifiedBootHash on this image is 64 zeros.
# The matching property is empty, so the boot scan reports a missing digest.
DIGEST=0000000000000000000000000000000000000000000000000000000000000000
resetprop -n ro.boot.vbmeta.digest "$DIGEST"
