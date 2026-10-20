#!/bin/sh
# Creates the private files bucket and an API access key limited to that bucket. Safe to re-run.
set -eu

mc alias set local "$MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null

mc mb --ignore-existing "local/$S3_BUCKET"
mc anonymous set private "local/$S3_BUCKET"

policy="/tmp/${S3_BUCKET}-rw.json"
cat >"$policy" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket", "s3:ListBucketMultipartUploads"],
      "Resource": ["arn:aws:s3:::${S3_BUCKET}"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
      "Resource": ["arn:aws:s3:::${S3_BUCKET}/*"]
    }
  ]
}
EOF

mc admin policy create local "${S3_BUCKET}-rw" "$policy" >/dev/null
mc admin user add local "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null
mc admin policy attach local "${S3_BUCKET}-rw" --user "$S3_ACCESS_KEY" >/dev/null 2>&1 || true

echo "bucket local/$S3_BUCKET ready (private); access key $S3_ACCESS_KEY has policy ${S3_BUCKET}-rw"
