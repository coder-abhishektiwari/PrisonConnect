-- Fix existing call_rooms records that have empty strings for offer/answer
-- Replace empty strings with null so they deserialize correctly
UPDATE call_rooms 
SET webrtc_signaling = jsonb_set(
    jsonb_set(webrtc_signaling, '{offer}', 'null'::jsonb),
    '{answer}', 
    'null'::jsonb
)
WHERE webrtc_signaling->>'offer' = '' OR webrtc_signaling->>'answer' = '';