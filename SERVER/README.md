# MachineVisor Image Receiver (FastAPI)

## Setup

1. Create/activate a virtual environment (Windows PowerShell):
```powershell
python -m venv .venv
./.venv/Scripts/Activate.ps1
```

2. Install dependencies:
```powershell
pip install -r requirements.txt
```

3. Run the server:
```powershell
python main.py
```
Server listens on `http://0.0.0.0:8000`.

## API
- GET /health → `{ status: "ok", time: ... }`
- POST /upload (multipart/form-data)
  - Fields:
    - image: file (jpeg/png)
    - device_id: optional string
    - pitch_deg: optional float
    - roll_deg: optional float
    - seq: optional integer
  - Saves file to `SERVER/uploads/` with timestamped filename.

## Test upload (PowerShell)
```powershell
Invoke-WebRequest -Uri http://localhost:8000/upload -Method POST -Form @{
  image=Get-Item ./sample.jpg;
  device_id="test-device";
  pitch_deg="12.3";
  roll_deg="78.0";
  seq="1"
}
```

Or with curl:
```bash
curl -F image=@sample.jpg -F device_id=test-device -F pitch_deg=12.3 -F roll_deg=78.0 -F seq=1 http://localhost:8000/upload
```


