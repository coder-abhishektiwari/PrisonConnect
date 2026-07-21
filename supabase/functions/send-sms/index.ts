import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  // Handle CORS preflight requests
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const { to, message } = await req.json()

    if (!to || !message) {
      throw new Error('Missing "to" or "message" in request body')
    }

    // Twilio credentials from environment variables
    const accountSid = Deno.env.get('TWILIO_ACCOUNT_SID')
    const authToken = Deno.env.get('TWILIO_AUTH_TOKEN')
    const fromNumber = Deno.env.get('TWILIO_PHONE_NUMBER')

    if (!accountSid || !authToken || !fromNumber) {
      throw new Error('Twilio credentials not configured in environment variables')
    }

    // Ensure "to" number starts with + (Twilio requirement for E.164)
    // The Android app cleans non-digits, so we add it back if missing
    const formattedTo = to.startsWith('+') ? to : `+${to}`

    console.log(`Sending SMS to ${formattedTo}: ${message}`)

    // Basic Auth for Twilio API
    const authHeader = btoa(`${accountSid}:${authToken}`)

    const response = await fetch(
      `https://api.twilio.org/2010-04-01/Accounts/${accountSid}/Messages.json`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'Authorization': `Basic ${authHeader}`,
        },
        body: new URLSearchParams({
          To: formattedTo,
          From: fromNumber,
          Body: message,
        }),
      }
    )

    const result = await response.json()

    if (!response.ok) {
      console.error('Twilio API error:', result)
      return new Response(
        JSON.stringify({ error: result.message || 'Failed to send SMS via Twilio' }),
        {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          status: response.status,
        }
      )
    }

    return new Response(
      JSON.stringify({ success: true, sid: result.sid }),
      {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 200,
      }
    )

  } catch (error) {
    console.error('Edge Function error:', error.message)
    return new Response(
      JSON.stringify({ error: error.message }),
      {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 400,
      }
    )
  }
})
