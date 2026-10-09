// Prints a fresh Ed25519 private JWK for the SIGNING_KEY secret.
const { privateKey } = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
const jwk = await crypto.subtle.exportKey("jwk", privateKey);
jwk.kid = new Date().toISOString().slice(0, 10) + "-" + crypto.randomUUID().slice(0, 8);
process.stdout.write(JSON.stringify(jwk));
