# Testing Moderation Edge Cases

The unit tests in `test_verdict.py` cover the decision logic with synthetic scores. To test the full pipeline (local model scoring + Azure escalation), you need to upload videos and observe their `maxScore` and verdict.

## Understanding the Boundaries

```
Score Range | Local Verdict | Azure Escalation | Final Status
─────────────────────────────────────────────────────────────
< 0.60      | APPROVED      | None             | PUBLISHED
0.60-0.89   | REVIEW        | Yes (top 3)      | Depends on Azure
0.90+       | REJECTED*     | None             | REJECTED
            | (if 2+ frames |                  |
            |  > 0.60)      |                  |
```

*If maxScore >= 0.90 but only 1 frame > 0.60, verdict is REVIEW instead.

## The Gap You Need to Test

You can easily test:
- ✓ Clean videos (gym) → maxScore < 0.60 → APPROVED
- ✓ Explicit videos (100% NSFW) → maxScore >= 0.90 + 2 frames → REJECTED

You **cannot** easily test:
- ✗ Videos with maxScore in the 0.60-0.89 range (REVIEW band)
- ✗ Azure escalation verdicts (need REVIEW videos, then see what Azure decides)
- ✗ Exact boundary behavior (0.59 vs 0.60, 0.89 vs 0.90)

## Strategy to Fill the Gap

### Option 1: Unit Tests Only (Already Done)
```bash
make moderation-test   # Runs test_verdict.py with synthetic scores
```

New boundary tests added:
- `test_exact_review_boundary_low`: 0.59 (APPROVED) vs 0.60 (REVIEW)
- `test_exact_reject_boundary_high`: 0.89 (REVIEW) vs 0.90 (REVIEW) vs 0.90+2 frames (REJECTED)
- `test_review_band_boundary_high`: maxScore 0.89 with multiple suspicious frames

**Limitation:** Tests the decision math, not the model's actual scoring.

### Option 2: Synthetic Video/Image Creation (For Integration Testing)

Create Python script to generate test images with known NSFW "appearance" and upload them:

```python
# Create a test image that *looks* borderline (PIL manipulation)
from PIL import Image
import numpy as np

def create_borderline_image():
    """Generate image with mild skin tones (might score 0.65-0.75)."""
    img = Image.new('RGB', (640, 480))
    # Add skin-toned areas (high saturation in certain hue ranges)
    pixels = np.array(img)
    pixels[100:200, 100:200] = [220, 160, 140]  # Skin tone
    return Image.fromarray(pixels)

# This is a guess — PIL alone can't guarantee what the model will score it.
# You'd upload it, check the video.moderation.maxScore, iterate.
```

**Limitation:** Synthetic images may not trigger realistic model scores. The model was trained on photographs, not PIL-generated images.

### Option 3: Real Image Editing (Recommended)

1. **For REVIEW band (0.60-0.89):**
   - Start with a known clean image (low NSFW score)
   - Gradually add "ambiguous" content: body parts, suggestive poses (not explicit)
   - Blend with clean background
   - Upload, check score, adjust

2. **For near-rejection (0.85-0.95):**
   - Explicit imagery with partial censoring
   - Or strategic cropping to reduce explicit area
   - Upload, check score, iterate

3. **For Azure escalation testing (need REVIEW verdict + Azure enabled):**
   - Once you have REVIEW-band videos, configure Azure credentials
   - Upload, watch admin console for `modelVersion` codes:
     - `nsfw-v1+azure-sev0` → Azure approved it
     - `nsfw-v1+azure-sev2` → Azure sent it back to review
     - `nsfw-v1+azure-sev4` → Azure rejected it

## Practical Testing Steps

### Step 1: Verify Unit Tests Pass
```bash
cd services/moderation-service
make moderation-test
```

### Step 2: Create Test Image
- Find/create an image that *might* score in the REVIEW band
- Save as JPEG

### Step 3: Upload via Postman or curl
```bash
# Encode image as base64
base64 -i test.jpg -o test.jpg.b64

# Create moderation request
curl -X POST http://localhost:8099/moderate \
  -H "Content-Type: application/json" \
  -d '{
    "frames": ["<base64-content-of-test.jpg.b64>"]
  }'
```

Response includes:
```json
{
  "verdict": "REVIEW",
  "maxScore": 0.67,
  "suspiciousFrames": 3,
  "modelVersion": "nsfw-v1"
}
```

### Step 4: Upload Video with That Frame
- Extract/use frames from step 2 in a video upload
- Check video.moderation on the video document in MongoDB
- Verify verdict matches the frame scores

### Step 5: If Azure Enabled
- Wait for video to enter MODERATION_PENDING state
- Check if it goes to PUBLISHED, PENDING_REVIEW, or REJECTED
- Check modelVersion for Azure escalation codes

## Current Test Coverage

| Scenario | Covered? | How |
|----------|----------|-----|
| Very clean (< 0.10) | ✓ | Unit test `test_all_clean_frames_are_approved` |
| Clean/approved (0.01-0.59) | ✓ | Unit test |
| Exactly at review threshold (0.60) | ✓ | New: `test_exact_review_boundary_low` |
| REVIEW band (0.60-0.89) | ✗ | Needs real/synthetic image upload |
| At reject threshold (0.90) | ✓ | New: `test_exact_reject_boundary_high` |
| Explicit/rejected (0.90+) | ✓ | Unit test + manual (gym video passed, 100% NSFW rejected) |
| Azure escalation path | ⚠️ | Enabled but untested (needs REVIEW-band videos first) |

## Debugging: Why Isn't My Video in REVIEW Band?

**Symptom:** Every test video is either APPROVED or REJECTED, never REVIEW.

**Likely causes:**
1. **Model not installed or wrong model:** Check `make moderation-test` passes first
2. **Model scored differently than expected:** NSFW models are tuned for internet/photo content; synthetic or heavily edited images may score differently
3. **Video preprocessing:** The 10 frames are sampled evenly across the whole video. One "bad" frame in a sea of clean ones might not cross 0.60
4. **Timing:** The model loads once at startup. Changes to THRESHOLDS require `docker compose up -d --force-recreate moderation-service`

**Debug steps:**
```bash
# Check model loaded and thresholds
curl http://localhost:8099/config

# Check service health
curl http://localhost:8099/health

# Re-examine scores in the response
# If maxScore is way off, it's a model behavior, not a threshold issue
```

## Tuning Thresholds

If you find the boundaries don't work for your use case:

```bash
# In docker-compose.yml, moderation-service:
# environment:
#   MODERATION_REVIEW_THRESHOLD: "0.50"  # Lower → more videos in REVIEW
#   MODERATION_REJECT_THRESHOLD: "0.80"  # Lower → more videos REJECTED
#   MODERATION_MIN_REJECT_FRAMES: "1"    # Lower → REJECTED on single frame

docker compose up -d --force-recreate moderation-service
```

**Important:** Remember to bump `MODERATION_MODEL_VERSION` when changing thresholds, so past verdicts can be attributed correctly.
