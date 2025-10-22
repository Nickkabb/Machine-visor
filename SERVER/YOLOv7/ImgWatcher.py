import os

import cv2
import numpy as np
from ultralytics import YOLO
from config import small_objects, big_objects


os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
model = YOLO(r"G:\AndroidStudioApps\NervAria\MachineVisor\SERVER\yolov8s.pt")
image = r"G:\AndroidStudioApps\NervAria\MachineVisor\SERVER\76328043-group-of-business-people-walking-at-street.jpg"
def get_grid_positions(x1, y1, x2, y2, img_width, img_height):
    """
    Determine which of the 9 grid boxes a bounding box occupies.
    Returns a simplified description of the object's location.
    """
    # Calculate grid boundaries
    third_width = img_width / 3
    third_height = img_height / 3
    
    # Determine which grid columns the object spans
    h_positions = []
    if x1 < third_width:  # Starts in left column
        h_positions.append("left")
    if x1 < 2 * third_width and x2 > third_width:  # Spans or touches middle column
        h_positions.append("middle")
    if x2 > 2 * third_width:  # Ends in right column
        h_positions.append("right")
    
    # Determine which grid rows the object spans
    v_positions = []
    if y1 < third_height:  # Starts in upper row
        v_positions.append("upper")
    if y1 < 2 * third_height and y2 > third_height:  # Spans or touches center row
        v_positions.append("center")
    if y2 > 2 * third_height:  # Ends in lower row
        v_positions.append("lower")
    
    # Check for full coverage in any direction
    if len(h_positions) == 3:  # Spans all horizontal positions
        if len(v_positions) == 3:  # Spans all vertical positions
            return ["entire image"]
        elif len(v_positions) == 2:  # Spans 2 vertical positions
            if "upper" in v_positions and "center" in v_positions:
                return ["upper half"]
            elif "center" in v_positions and "lower" in v_positions:
                return ["lower half"]
            else:
                return ["center area"]
        else:  # Single vertical position
            return [f"entire {v_positions[0]}"]
    
    elif len(v_positions) == 3:  # Spans all vertical positions
        if len(h_positions) == 2:  # Spans 2 horizontal positions
            if "left" in h_positions and "middle" in h_positions:
                return ["left half"]
            elif "middle" in h_positions and "right" in h_positions:
                return ["right half"]
            else:
                return ["center area"]
        else:  # Single horizontal position
            return [f"entire {h_positions[0]}"]
    
    # Check for half coverage
    elif len(h_positions) == 2 and len(v_positions) == 2:
        if "left" in h_positions and "middle" in h_positions:
            if "upper" in v_positions and "center" in v_positions:
                return ["upper left area"]
            elif "center" in v_positions and "lower" in v_positions:
                return ["lower left area"]
            else:
                return ["left area"]
        elif "middle" in h_positions and "right" in h_positions:
            if "upper" in v_positions and "center" in v_positions:
                return ["upper right area"]
            elif "center" in v_positions and "lower" in v_positions:
                return ["lower right area"]
            else:
                return ["right area"]
        elif "upper" in v_positions and "center" in v_positions:
            return ["upper area"]
        elif "center" in v_positions and "lower" in v_positions:
            return ["lower area"]
    
    # Generate specific grid combinations for partial coverage
    grid_positions = []
    for v_pos in v_positions:
        for h_pos in h_positions:
            grid_positions.append(f"{v_pos} {h_pos}")
    
    return grid_positions

def AnalyzeImage(image, details=False):
    # Load a lightweight YOLOv8 model. You can switch to 'yolov8s.pt'/'yolov8m.pt' for higher accuracy.
    img = cv2.imread(image)
    
    # Get image dimensions
    height, width, channels = img.shape
    # print(f"Image dimensions: Width={width}, Height={height}, Channels={channels}")

    # Calculate grid boundaries for visualization
    third_width = width / 3
    third_height = height / 3

    # Draw grid lines on the image for visualization
    img_with_grid = img.copy()
    # Vertical lines
    cv2.line(img_with_grid, (int(third_width), 0), (int(third_width), height), (128, 128, 128), 2)
    cv2.line(img_with_grid, (int(2 * third_width), 0), (int(2 * third_width), height), (128, 128, 128), 2)
    # Horizontal lines
    cv2.line(img_with_grid, (0, int(third_height)), (width, int(third_height)), (128, 128, 128), 2)
    cv2.line(img_with_grid, (0, int(2 * third_height)), (width, int(2 * third_height)), (128, 128, 128), 2)
    
    results = model.predict(img, verbose=False)

    # Extract detected objects
    detections = results[0]
    detected_objects = []
    summary = {
        'image': {
            'width': width,
            'height': height,
            'channels': channels
        },
        'objects': [],
        'info': {},
        'details': details
    }
    detected_count = 0
    info_lines = []

    if len(detections.boxes) > 0:
        # Filter objects based on details parameter
        filtered_objects = []
        for box in detections.boxes:
            class_id = int(box.cls[0])
            class_name = model.names[class_id]
            # If details=False, only show big objects
            if not details and class_name in small_objects:
                continue
            filtered_objects.append(box)
        if filtered_objects:
            detected_count = len(filtered_objects)
            info_lines.append(f"Detected {len(filtered_objects)} objects:")
            if not details:
                info_lines.append("(Small objects hidden - use details=True to show them)")
            # info_lines.append("-" * 60)
            for i, box in enumerate(filtered_objects):
                # Get class name and confidence
                class_id = int(box.cls[0])
                confidence = float(box.conf[0])
                class_name = model.names[class_id]
                # Get bounding box coordinates
                x1, y1, x2, y2 = box.xyxy[0].tolist()
                # Calculate object center point
                center_x = (x1 + x2) / 2
                center_y = (y1 + y2) / 2
                # Determine all grid positions the object occupies
                grid_positions = get_grid_positions(x1, y1, x2, y2, width, height)
                # Calculate object dimensions
                obj_width = x2 - x1
                obj_height = y2 - y1
                # Store detection info
                detection_info = {
                    'id': i + 1,
                    'class': class_name,
                    'confidence': confidence,
                    'bbox': [x1, y1, x2, y2],
                    'center': [center_x, center_y],
                    'grid_positions': grid_positions,
                    'width': obj_width,
                    'height': obj_height
                }
                detected_objects.append(detection_info)
                # Collect string versions for info (optional, in case useful for UI logs)
                info_lines.append({
                    'object': i+1,
                    'class': class_name,
                    'confidence': round(confidence,2),
                    'bbox': [round(val,1) for val in [x1,y1,x2,y2]],
                    'center': [round(center_x,1), round(center_y,1)],
                    'grid_location': grid_positions,
                    'size_px': [round(obj_width,1), round(obj_height,1)]
                })
        else:
            info_lines.append("No big objects detected in the image.")
            if not details:
                info_lines.append("(Small objects may be present - use details=True to see them)")
    else:
        info_lines.append("No objects detected in the image.")

    summary['objects'] = detected_objects
    summary['info'] = info_lines
    summary['object_count'] = detected_count
    # Remove display code for API usage
    # annotated = results[0].plot()
    # cv2.line(annotated, (int(third_width), 0), (int(third_width), height), (128, 128, 128), 2)
    # cv2.line(annotated, (int(2 * third_width), 0), (int(2 * third_width), height), (128, 128, 128), 2)
    # cv2.line(annotated, (0, int(third_height)), (width, int(third_height)), (128, 128, 128), 2)
    # cv2.line(annotated, (0, int(2 * third_height)), (width, int(2 * third_height)), (128, 128, 128), 2)
    # cv2.imshow('YOLOv8 Detections with 3x3 Grid (press q to quit)', annotated)
    # cv2.waitKey(0)
    # cv2.destroyAllWindows()
    return summary

# Example usage:
# AnalyzeImage(image)  # Only shows big objects (default)
# AnalyzeImage(image, details=True)  # Shows all objects including small ones

# Comment out or remove demo call
# AnalyzeImage(image, details=False)