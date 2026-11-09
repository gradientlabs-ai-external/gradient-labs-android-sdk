# Consumer rules merged into host apps that minify with R8.
#
# The SDK itself needs nothing kept: it uses no reflection or serialization
# (org.json and HttpURLConnection are framework classes, the WebView bridge is
# installed programmatically), and its activity/provider entry points are kept
# via the merged manifest. Tink (pulled in by androidx.security-crypto) ships
# its own consumer rules inside the tink-android artifact.
