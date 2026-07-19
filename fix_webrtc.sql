UPDATE call_rooms 
SET webrtc_signaling = '{"offer":null,"answer":null,"iceCandidates":[]}' 
WHERE webrtc_signaling->>'offer' = '' OR webrtc_signaling->>'answer' = '';