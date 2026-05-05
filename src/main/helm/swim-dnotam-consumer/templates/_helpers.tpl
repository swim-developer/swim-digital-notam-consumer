{{- define "swim-dnotam-consumer.labels" -}}
app: {{ .Values.appName }}
app.kubernetes.io/part-of: swim-dnotam
{{- end }}

{{- define "swim-dnotam-consumer.selectorLabels" -}}
app: {{ .Values.appName }}
{{- end }}
