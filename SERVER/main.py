from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse
from pathlib import Path
from datetime import datetime
import uvicorn
from dotenv import load_dotenv
import os
from YOLOv7.ImgWatcher import AnalyzeImage
load_dotenv()
app = FastAPI(title="MachineVisor Image Receiver", version="1.0.0")
BASE_DIR = Path(__file__).parent
UPLOADS_DIR = BASE_DIR / "uploads"
UPLOADS_DIR.mkdir(parents=True, exist_ok=True)

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "time": datetime.utcnow().isoformat() + "Z"}

@app.post("/upload")
async def upload_image(
    image: UploadFile = File(..., description="JPEG/PNG image file"),
    pitch_deg: float = Form(..., description="Pitch angle in degrees"),
    roll_deg: float = Form(..., description="Roll angle in degrees"),
    seq: int = Form(..., description="Sequence number"),
    details: bool = Form(default=False, description="Include small objects in analysis"),
):
    # Validate content type
    if image.content_type not in {"image/jpeg", "image/png"}:
        return JSONResponse(status_code=400, content={"error": "Unsupported content type"})

    timestamp = datetime.utcnow().strftime("%Y%m%dT%H%M%S%fZ")
    ext = ".jpg" if image.content_type == "image/jpeg" else ".png"

    # Build filename with metadata
    filename = f"{timestamp}_p{pitch_deg:.1f}_r{roll_deg:.1f}_seq{seq:06d}{ext}"
    dest = UPLOADS_DIR / filename

    try:
        # Save file
        with dest.open("wb") as f:
            f.write(await image.read())

        # Analyze the image using the AnalyzeImage function
        analysis_result = AnalyzeImage(str(dest), details=details)

        return {
            "status": "success",
            "saved_as": filename,
            "size_bytes": dest.stat().st_size,
            "content_type": image.content_type,
            "pitch_deg": pitch_deg,
            "roll_deg": roll_deg,
            "seq": seq,
            "details": details,
            "analysis": analysis_result
        }
    
    except Exception as e:
        return JSONResponse(
            status_code=500, 
            content={
                "error": "Failed to process image", 
                "message": str(e)
            }
        )

if __name__ == "__main__":
    host = os.getenv("IP", "0.0.0.0")
    port = int(os.getenv("PORT", "8000"))
    uvicorn.run("main:app", host=host, port=port, reload=True)