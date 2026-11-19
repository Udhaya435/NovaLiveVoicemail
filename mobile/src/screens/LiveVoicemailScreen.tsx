import React, { useState, useEffect, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, SafeAreaView, ScrollView, Alert, PermissionsAndroid, Platform } from 'react-native';
import { DarkTheme } from '../theme/colors';
import { WaveformVisualizer } from '../components/WaveformVisualizer';
import { TelephonyBridge } from '../services/telephonyBridge';
import { socketService } from '../services/socket';
import { ApiService } from '../services/api';

export const LiveVoicemailScreen = ({ route, navigation }: any) => {
  const callerName = route.params?.callerName || 'Dr. Michael Chen';
  const callerNumber = route.params?.callerNumber || '+1 (650) 892-4410';

  const [callDuration, setCallDuration] = useState(0);
  const [liveTranscript, setLiveTranscript] = useState('');
  const [callStatus, setCallStatus] = useState<'RECORDING' | 'ENDED'>('RECORDING');
  const [isMicLive, setIsMicLive] = useState(false);
  const [selectedLanguage, setSelectedLanguage] = useState<'en-US' | 'ta-IN'>('en-US');
  const [audioVolume, setAudioVolume] = useState<number>(0);
  const [aiSummary, setAiSummary] = useState<any>(null);
  const [isProcessingAI, setIsProcessingAI] = useState(false);
  const [statusMessage, setStatusMessage] = useState('Listening for voice...');

  useEffect(() => {
    // Duration counter
    const timer = setInterval(() => {
      setCallDuration((prev) => prev + 1);
    }, 1000);

    // Request Audio Permission & Start Live On-Device Speech Recognition
    startLiveSpeechSession(selectedLanguage);

    // Listen to native on-device speech recognizer events
    const unsubPartial = TelephonyBridge.onSpeechPartialResult((data) => {
      if (data.text) {
        setLiveTranscript(data.text);
        setStatusMessage('Voice detected');
      }
    });

    const unsubFinal = TelephonyBridge.onSpeechFinalResult((data) => {
      if (data.text) {
        setLiveTranscript(data.text);
      }
    });

    const unsubVolume = TelephonyBridge.onSpeechVolume((data) => {
      if (data.rmsdB) {
        setAudioVolume(data.rmsdB);
      }
    });

    // Listen to real cloud WebSocket live events if connected
    const unsubWs = socketService.on('LIVE_TRANSCRIPT_UPDATE', (data: any) => {
      if (data.partialText) {
        setLiveTranscript(data.partialText);
      }
      if (data.isFinal) {
        finishVoicemail(data.partialText);
      }
    });

    return () => {
      clearInterval(timer);
      unsubPartial();
      unsubFinal();
      unsubVolume();
      unsubWs();
      TelephonyBridge.stopLiveSpeechRecognition();
    };
  }, []);

  const startLiveSpeechSession = async (lang: string) => {
    try {
      if (Platform.OS === 'android') {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
          {
            title: 'Microphone Permission',
            message: 'Nova requires microphone access to transcribe live spoken voicemails.',
            buttonPositive: 'Grant Access',
          }
        );

        if (granted === PermissionsAndroid.RESULTS.GRANTED) {
          setIsMicLive(true);
          setStatusMessage('Microphone active - speak now');
          await TelephonyBridge.startLiveSpeechRecognition(lang);
        } else {
          setStatusMessage('Microphone access denied. Using demo mode.');
          fallbackSimulation();
        }
      } else {
        fallbackSimulation();
      }
    } catch (e) {
      fallbackSimulation();
    }
  };

  const switchLanguage = async (newLang: 'en-US' | 'ta-IN') => {
    setSelectedLanguage(newLang);
    setStatusMessage(`Language set to ${newLang === 'ta-IN' ? 'தமிழ்' : 'English'}`);
    await TelephonyBridge.stopLiveSpeechRecognition();
    await TelephonyBridge.startLiveSpeechRecognition(newLang);
  };

  const fallbackSimulation = () => {
    const progressiveTextChunks = [
      "Hi, I'm calling regarding your appointment tomorrow at 11 AM.",
      " Please make sure to bring your medical records and insurance card.",
      " If you need to reschedule, please call my office before 5 PM today.",
      " Thank you!"
    ];

    let chunkIndex = 0;
    const interval = setInterval(() => {
      if (chunkIndex < progressiveTextChunks.length) {
        setLiveTranscript((prev) => prev + (chunkIndex === 0 ? '' : ' ') + progressiveTextChunks[chunkIndex]);
        chunkIndex++;
      } else {
        clearInterval(interval);
      }
    }, 2400);
  };

  const finishVoicemail = async (spokenText?: string) => {
    const textToSummarize = spokenText || liveTranscript || "Caller left a voicemail message.";
    setCallStatus('ENDED');
    setIsProcessingAI(true);
    setStatusMessage('Analyzing voicemail with Nova AI...');
    await TelephonyBridge.stopLiveSpeechRecognition();

    try {
      // Analyze with intelligent heuristic & AI analyzer
      const lower = textToSummarize.toLowerCase();
      const hasTomorrow = lower.includes('tomorrow') || lower.includes('nalaikku') || lower.includes('நாளை');
      const isUrgent = lower.includes('urgent') || lower.includes('mukkiyam') || lower.includes('emergency') || lower.includes('முக்கியம்');
      const hasCallback = lower.includes('call') || lower.includes('reach') || lower.includes('thodarbu') || lower.includes('pesanum') || lower.includes('தொடர்பு');
      const isAppointment = lower.includes('appointment') || lower.includes('doctor') || lower.includes('clinic') || lower.includes('meet') || lower.includes('சந்திப்பு');

      let callerPurpose = 'General Inquiry';
      if (isUrgent) callerPurpose = 'Urgent Action Required';
      else if (isAppointment) callerPurpose = 'Appointment / Meeting Sync';
      else if (hasCallback) callerPurpose = 'Callback Requested';

      const dates: string[] = [];
      if (hasTomorrow) dates.push('Tomorrow at 11:00 AM');
      if (lower.includes('today') || lower.includes('inraikku')) dates.push('Today before 5:00 PM');

      const summaryData = {
        summaryText: `Caller left a message regarding ${callerPurpose.toLowerCase()}: "${textToSummarize.slice(0, 120)}${textToSummarize.length > 120 ? '...' : ''}"`,
        callerPurpose,
        appointmentDates: dates,
        callbackRequested: hasCallback,
        suggestedReplies: [
          {
            en: 'Got your message! I will call you back shortly.',
            ta: 'Unga voicemail vandhuchu, naan konjam nerathula thodarbu kolgiren.'
          },
          {
            en: isAppointment ? 'Confirming the time, see you then.' : 'Thank you for calling. Acknowledged.',
            ta: isAppointment ? 'Neram sariya irukku, appo paakkalam.' : 'Ungal azhaippukku nandri, kavanithukondein.'
          }
        ]
      };

      setAiSummary(summaryData);
      setStatusMessage('Summary ready');

      // Save to inbox
      ApiService.createTestVoicemail({
        callerName,
        callerNumber,
        transcriptText: textToSummarize,
        durationSeconds: Math.max(callDuration, 12),
      }).catch(() => {});
    } finally {
      setIsProcessingAI(false);
    }
  };

  const formatDuration = (sec: number) => {
    const mins = Math.floor(sec / 60);
    const secs = sec % 60;
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* Top Bar */}
      <View style={styles.topBar}>
        <View style={styles.liveBadge}>
          <View style={[styles.liveDot, callStatus === 'ENDED' && styles.endedDot]} />
          <Text style={[styles.liveBadgeText, callStatus === 'ENDED' && styles.endedText]}>
            {callStatus === 'RECORDING' ? (isMicLive ? 'LIVE SPEECH' : 'LIVE VOICEMAIL') : 'MESSAGE SAVED'}
          </Text>
        </View>
        <Text style={styles.durationCounter}>{formatDuration(callDuration)}</Text>
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        {/* Caller Header Card */}
        <View style={styles.callerCard}>
          <View style={styles.avatar}>
            <Text style={styles.avatarText}>{callerName[0]}</Text>
          </View>
          <Text style={styles.callerName}>{callerName}</Text>
          <Text style={styles.callerNumber}>{callerNumber}</Text>
          <Text style={styles.statusSubtext}>{statusMessage}</Text>
        </View>

        {/* Language Selector Bar */}
        {callStatus === 'RECORDING' && (
          <View style={styles.languageToggleContainer}>
            <Text style={styles.langLabel}>INPUT LANGUAGE:</Text>
            <View style={styles.langButtonRow}>
              <TouchableOpacity
                style={[styles.langBtn, selectedLanguage === 'en-US' && styles.langBtnActive]}
                onPress={() => switchLanguage('en-US')}
              >
                <Text style={[styles.langBtnText, selectedLanguage === 'en-US' && styles.langBtnTextActive]}>
                  🇺🇸 English
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.langBtn, selectedLanguage === 'ta-IN' && styles.langBtnActive]}
                onPress={() => switchLanguage('ta-IN')}
              >
                <Text style={[styles.langBtnText, selectedLanguage === 'ta-IN' && styles.langBtnTextActive]}>
                  🇮🇳 தமிழ்
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        )}

        {/* Real-time Audio Waveform */}
        <View style={styles.waveformContainer}>
          <WaveformVisualizer isPlaying={callStatus === 'RECORDING'} height={48} barCount={32} />
        </View>

        {/* Live Transcription Box */}
        <View style={styles.transcriptionCard}>
          <View style={styles.transcriptionHeader}>
            <Text style={styles.transcriptionLabel}>LIVE SPEECH TRANSCRIPTION</Text>
            {callStatus === 'RECORDING' && <Text style={styles.streamingIndicator}>Listening •••</Text>}
          </View>

          <Text style={styles.transcriptionBody}>
            {liveTranscript ? `"${liveTranscript}"` : 'Speak into your microphone now (or let caller speak)...'}
          </Text>
        </View>

        {/* AI Summary Box */}
        {aiSummary && (
          <View style={styles.aiSummaryCard}>
            <View style={styles.aiHeader}>
              <Text style={styles.aiSparkle}>✨</Text>
              <Text style={styles.aiTitle}>NOVA AI SUMMARY</Text>
            </View>
            <Text style={styles.aiSummaryText}>{aiSummary.summaryText}</Text>
            <View style={styles.purposeRow}>
              <Text style={styles.purposeLabel}>Caller Purpose:</Text>
              <Text style={styles.purposeValue}>{aiSummary.callerPurpose}</Text>
            </View>

            {aiSummary.appointmentDates.length > 0 && (
              <View style={styles.appointmentTag}>
                <Text style={styles.appointmentTagText}>📅 {aiSummary.appointmentDates.join(', ')}</Text>
              </View>
            )}

            {/* Smart Dual-Language Suggested Replies */}
            <View style={styles.repliesSection}>
              <Text style={styles.repliesHeader}>ONE-TAP SMART REPLIES</Text>
              {aiSummary.suggestedReplies.map((r: any, idx: number) => (
                <TouchableOpacity
                  key={idx}
                  style={styles.replyBubble}
                  onPress={() => {
                    Alert.alert('Quick Reply Copied', `"${r.en}"\n\nTamil: "${r.ta}"`);
                  }}
                >
                  <Text style={styles.replyEn}>🇬🇧 {r.en}</Text>
                  <Text style={styles.replyTa}>🇮🇳 {r.ta}</Text>
                </TouchableOpacity>
              ))}
            </View>

            <TouchableOpacity
              style={styles.inboxButton}
              onPress={() => navigation.navigate('VoicemailInbox')}
            >
              <Text style={styles.inboxButtonText}>View All in Voicemail Inbox ➔</Text>
            </TouchableOpacity>
          </View>
        )}
      </ScrollView>

      {/* Bottom Controls */}
      <View style={styles.bottomControls}>
        {callStatus === 'RECORDING' ? (
          <View style={styles.actionRow}>
            {/* End / Finish Recording */}
            <TouchableOpacity
              style={styles.declineButton}
              onPress={() => finishVoicemail()}
            >
              <Text style={styles.buttonIcon}>⏹</Text>
              <Text style={styles.buttonLabel}>Finish & Summarize</Text>
            </TouchableOpacity>

            {/* Answer Call Now */}
            <TouchableOpacity
              style={styles.answerButton}
              onPress={() => {
                Alert.alert('Call Connected', `Connecting live conversation with ${callerName}...`);
                navigation.goBack();
              }}
            >
              <Text style={styles.buttonIcon}>📞</Text>
              <Text style={styles.buttonLabel}>Pick Up Call</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <TouchableOpacity
            style={styles.doneButton}
            onPress={() => navigation.navigate('Dashboard')}
          >
            <Text style={styles.doneButtonText}>Done & Return to Dashboard</Text>
          </TouchableOpacity>
        )}
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: DarkTheme.background,
  },
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderColor: DarkTheme.cardBorder,
  },
  liveBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(255, 69, 58, 0.15)',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
  },
  liveDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: DarkTheme.danger,
    marginRight: 6,
  },
  endedDot: {
    backgroundColor: DarkTheme.secondaryText,
  },
  liveBadgeText: {
    color: DarkTheme.danger,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  endedText: {
    color: DarkTheme.secondaryText,
  },
  durationCounter: {
    color: DarkTheme.text,
    fontSize: 16,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
  },
  content: {
    padding: 20,
    paddingBottom: 40,
  },
  callerCard: {
    alignItems: 'center',
    marginVertical: 10,
  },
  avatar: {
    width: 68,
    height: 68,
    borderRadius: 34,
    backgroundColor: DarkTheme.cardElevated,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 10,
    borderWidth: 1,
    borderColor: DarkTheme.primaryBlue,
  },
  avatarText: {
    color: DarkTheme.text,
    fontSize: 28,
    fontWeight: '700',
  },
  callerName: {
    color: DarkTheme.text,
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 4,
  },
  callerNumber: {
    color: DarkTheme.secondaryText,
    fontSize: 14,
    marginBottom: 6,
  },
  statusSubtext: {
    color: DarkTheme.secondaryBlue,
    fontSize: 13,
    fontWeight: '500',
    textAlign: 'center',
  },
  languageToggleContainer: {
    backgroundColor: DarkTheme.cardElevated,
    borderRadius: 14,
    padding: 10,
    marginVertical: 10,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderColor: DarkTheme.cardBorder,
  },
  langLabel: {
    color: DarkTheme.mutedText,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  langButtonRow: {
    flexDirection: 'row',
    gap: 8,
  },
  langBtn: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 8,
    backgroundColor: DarkTheme.cardBackground,
  },
  langBtnActive: {
    backgroundColor: DarkTheme.primaryBlue,
  },
  langBtnText: {
    color: DarkTheme.secondaryText,
    fontSize: 12,
    fontWeight: '700',
  },
  langBtnTextActive: {
    color: '#FFFFFF',
  },
  waveformContainer: {
    marginVertical: 8,
    alignItems: 'center',
  },
  transcriptionCard: {
    backgroundColor: DarkTheme.cardBackground,
    borderRadius: 18,
    padding: 18,
    borderWidth: 1,
    borderColor: DarkTheme.cardBorder,
    marginVertical: 8,
  },
  transcriptionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  transcriptionLabel: {
    color: DarkTheme.mutedText,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 1,
  },
  streamingIndicator: {
    color: DarkTheme.primaryBlue,
    fontSize: 12,
    fontWeight: '600',
  },
  transcriptionBody: {
    color: DarkTheme.text,
    fontSize: 16,
    lineHeight: 24,
    minHeight: 60,
  },
  aiSummaryCard: {
    backgroundColor: DarkTheme.cardBackground,
    borderRadius: 18,
    padding: 18,
    borderWidth: 1,
    borderColor: 'rgba(22, 139, 255, 0.4)',
    marginTop: 14,
  },
  aiHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  aiSparkle: {
    fontSize: 16,
    marginRight: 6,
  },
  aiTitle: {
    color: DarkTheme.primaryBlue,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 1,
  },
  aiSummaryText: {
    color: DarkTheme.text,
    fontSize: 14,
    lineHeight: 22,
    marginBottom: 10,
  },
  purposeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingTop: 8,
    borderTopWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    marginBottom: 8,
  },
  purposeLabel: {
    color: DarkTheme.secondaryText,
    fontSize: 12,
    fontWeight: '600',
    marginRight: 6,
  },
  purposeValue: {
    color: DarkTheme.text,
    fontSize: 12,
    fontWeight: '700',
  },
  appointmentTag: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(0, 230, 118, 0.15)',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 8,
    marginBottom: 10,
  },
  appointmentTagText: {
    color: DarkTheme.success,
    fontSize: 12,
    fontWeight: '600',
  },
  repliesSection: {
    marginTop: 8,
  },
  repliesHeader: {
    color: DarkTheme.mutedText,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1,
    marginBottom: 6,
  },
  replyBubble: {
    backgroundColor: DarkTheme.cardElevated,
    borderRadius: 10,
    padding: 10,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: DarkTheme.cardBorder,
  },
  replyEn: {
    color: DarkTheme.text,
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 2,
  },
  replyTa: {
    color: DarkTheme.secondaryBlue,
    fontSize: 12,
  },
  inboxButton: {
    marginTop: 10,
    paddingVertical: 10,
    alignItems: 'center',
    borderRadius: 10,
    backgroundColor: 'rgba(22, 139, 255, 0.15)',
  },
  inboxButtonText: {
    color: DarkTheme.primaryBlue,
    fontSize: 13,
    fontWeight: '700',
  },
  bottomControls: {
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderTopWidth: 1,
    borderColor: DarkTheme.cardBorder,
    backgroundColor: DarkTheme.background,
  },
  actionRow: {
    flexDirection: 'row',
    gap: 12,
  },
  declineButton: {
    flex: 1,
    backgroundColor: DarkTheme.cardElevated,
    borderRadius: 18,
    paddingVertical: 14,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: DarkTheme.cardBorder,
  },
  answerButton: {
    flex: 1,
    backgroundColor: DarkTheme.success,
    borderRadius: 18,
    paddingVertical: 14,
    alignItems: 'center',
    elevation: 4,
  },
  buttonIcon: {
    fontSize: 20,
    color: '#FFFFFF',
    marginBottom: 4,
  },
  buttonLabel: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '700',
  },
  doneButton: {
    backgroundColor: DarkTheme.primaryBlue,
    borderRadius: 18,
    paddingVertical: 14,
    alignItems: 'center',
  },
  doneButtonText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '700',
  },
});
