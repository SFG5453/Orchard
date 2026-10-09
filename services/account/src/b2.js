import { AwsClient } from "aws4fetch";

// B2 speaks S3 here. The artwork domain may read publicly; writes stay signed.
function objectUrl(env, key) {
  const region = env.B2_REGION;
  const bucket = env.B2_BUCKET;
  if (!/^[a-z0-9-]+$/.test(region || "") || !/^[a-z0-9-]+$/.test(bucket || "") ||
      !env.B2_KEY_ID || !env.B2_APPLICATION_KEY) {
    throw new Error("B2 artwork storage is not configured");
  }
  return `https://s3.${region}.backblazeb2.com/${bucket}/${key}`;
}

async function request(env, key, init) {
  const client = new AwsClient({
    accessKeyId: env.B2_KEY_ID,
    secretAccessKey: env.B2_APPLICATION_KEY,
    service: "s3",
    region: env.B2_REGION,
  });
  const signed = await client.sign(objectUrl(env, key), init);
  return (env.B2_FETCH || fetch)(signed);
}

function expectSuccess(response, operation) {
  if (!response.ok) throw new Error(`B2 ${operation} failed with HTTP ${response.status}`);
  return response;
}

export async function putArtworkObject(env, key, bytes) {
  expectSuccess(await request(env, key, {
    method: "PUT",
    headers: { "content-type": "image/webp", "cache-control": "public, max-age=31536000, immutable" },
    body: bytes,
  }), "upload");
}

export async function getArtworkObject(env, key, method = "GET") {
  const response = await request(env, key, { method });
  if (response.status === 404) return null;
  return expectSuccess(response, "download");
}
