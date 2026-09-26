import base64
import binascii
import enum
from typing import Annotated
from uuid import UUID

from pydantic import AfterValidator, BaseModel


class MimeType(enum.Enum):
    svg = "image/svg+xml"
    png = "image/png"
    jpeg = "image/jpeg"


MAX_BLOB_SIZE_BYTES = 2 * 1024 * 1024

# headers for publicly served, user-uploaded blobs (no sniffing, no active content, cacheable)
BLOB_RESPONSE_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "sandbox",
    "Cache-Control": "public, max-age=3600",
}


def is_valid_base64(data: str) -> str:
    # base64 inflates the payload by 4/3, reject obviously oversized input before decoding it
    if len(data) > MAX_BLOB_SIZE_BYTES * 4 // 3 + 4:
        raise ValueError(f"Uploaded file exceeds the maximum size of {MAX_BLOB_SIZE_BYTES // (1024 * 1024)} MB")
    try:
        decoded = base64.b64decode(data)
    except binascii.Error as e:
        raise ValueError("Invalid base64 string") from e
    if len(decoded) > MAX_BLOB_SIZE_BYTES:
        raise ValueError(f"Uploaded file exceeds the maximum size of {MAX_BLOB_SIZE_BYTES // (1024 * 1024)} MB")
    return data


class NewBlob(BaseModel):
    data: Annotated[str, AfterValidator(is_valid_base64)]  # base64 encoded
    mime_type: str

    def data_as_bytes(self):
        return base64.b64decode(self.data)

    def is_known_mimetype(self):
        return self.mime_type == MimeType.svg


class Blob(BaseModel):
    id: UUID
    data: bytes
    mime_type: str


class EventDesign(BaseModel):
    bon_logo_blob_id: UUID | None
    app_logo_blob_id: UUID | None = None
    customer_logo_blob_id: UUID | None = None
    wristband_guide_blob_id: UUID | None = None
