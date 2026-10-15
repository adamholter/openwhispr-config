// Local Parakeet v3 speech-to-text on the Apple Neural Engine (FluidAudio).
//
//   OWFluidASR serve [port]           HTTP on 127.0.0.1 (default 6017)
//     GET  /health                    -> {"ready":true}
//     POST /transcribe                body: raw float32 LE, 16 kHz mono
//                                     -> {"text":..,"duration":..,"elapsedMs":..}
//   OWFluidASR batch out.json a.wav…  transcribe files (used for evaluation)
//
// The model stays loaded between requests, and requests are decoded one at a time.

import FluidAudio
import Foundation
import Network

let sampleRate = 16_000
let env = ProcessInfo.processInfo.environment
// Tunables (env overrides exist only for evaluation runs).
let useVad = env["OWASR_VAD"] != "0"
let vadThreshold = Float(env["OWASR_VAD_THRESHOLD"] ?? "") ?? 0.5
let speechGapSeconds = 0.3
// Clips shorter than this are decoded exactly as recorded; trimming pauses only pays off
// (and only removes invented words) on long recordings.
let vadMinSeconds = 30.0
let maxBodyBytes = 64 << 20 // ~17 min of float32 audio

actor Transcriber {
    private var manager: AsrManager?
    private var vad: VadManager?

    func load() async throws {
        let models = try await AsrModels.downloadAndLoad(version: .v3)
        let manager = AsrManager(config: .default)
        try await manager.loadModels(models)
        self.manager = manager
        if useVad { vad = try await VadManager(config: VadConfig(defaultThreshold: vadThreshold)) }
    }

    var ready: Bool { manager != nil }

    private func decode(_ samples: [Float]) async throws -> ASRResult {
        guard let manager else { throw ASRError.notInitialized }
        var state = TdtDecoderState.make(decoderLayers: await manager.decoderLayerCount)
        return try await manager.transcribe(samples, decoderState: &state)
    }

    // Long pauses are where Parakeet invents words, so only the detected speech
    // (with short joins) is decoded, in one pass to keep full context.
    private func speechOnly(_ samples: [Float]) async throws -> [Float] {
        guard let vad, Double(samples.count) >= vadMinSeconds * Double(sampleRate) else { return samples }
        var config = VadSegmentationConfig.default
        config.maxSpeechDuration = 3600
        config.speechPadding = 0.25
        let segments = try await vad.segmentSpeech(samples, config: config)
        guard !segments.isEmpty else { return samples }
        let gap = [Float](repeating: 0, count: Int(speechGapSeconds * Double(sampleRate)))
        var out: [Float] = []
        for segment in segments {
            let start = max(0, min(segment.startSample(sampleRate: sampleRate), samples.count))
            let end = max(start, min(segment.endSample(sampleRate: sampleRate), samples.count))
            if !out.isEmpty { out += gap }
            out += samples[start..<end]
        }
        return out
    }

    func transcribe(_ samples: [Float]) async throws -> ASRResult {
        let speech = try await speechOnly(samples)
        var result = try await decode(speech)
        if result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // Very short or quiet clips sometimes decode to nothing; silence
            // padding around the original audio usually recovers them.
            let pad = [Float](repeating: 0, count: sampleRate)
            result = try await decode(pad + samples + pad)
        }
        return result
    }
}

func jsonData(_ object: [String: Any]) -> Data {
    (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
}

// MARK: - Minimal HTTP/1.1 server (one request per connection)

final class Connection: @unchecked Sendable {
    let conn: NWConnection
    let transcriber: Transcriber
    var buffer = Data()

    init(_ conn: NWConnection, _ transcriber: Transcriber) {
        self.conn = conn
        self.transcriber = transcriber
    }

    func start() {
        conn.start(queue: .global())
        receive()
    }

    private func receive() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { data, _, done, error in
            if let data { self.buffer.append(data) }
            if self.buffer.count > maxBodyBytes + 65_536 { self.conn.cancel(); return }
            if let request = self.parse() {
                Task { await self.handle(request) }
            } else if done || error != nil {
                self.conn.cancel()
            } else {
                self.receive()
            }
        }
    }

    private func parse() -> (method: String, path: String, body: Data)? {
        guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 2 else { return ("BAD", "", Data()) }
        var length = 0
        var host = ""
        var hasOrigin = false
        for line in lines.dropFirst() {
            let kv = line.split(separator: ":", maxSplits: 1)
            guard kv.count == 2 else { continue }
            let name = kv[0].lowercased(), value = kv[1].trimmingCharacters(in: .whitespaces)
            if name == "content-length" { length = Int(value) ?? -1 }
            if name == "host" { host = value.lowercased() }
            if name == "origin" { hasOrigin = true }
        }
        // Local callers only: browsers always send Origin, and DNS-rebinding pages use a foreign Host.
        let hostName = host.split(separator: ":").first.map(String.init) ?? ""
        if hasOrigin || !["127.0.0.1", "localhost"].contains(hostName) { return ("FORBIDDEN", "", Data()) }
        if length < 0 || length > maxBodyBytes || length % 4 != 0 { return ("BAD", "", Data()) }
        let bodyStart = end.upperBound
        guard buffer.count - bodyStart >= length else { return nil }
        return (String(parts[0]), String(parts[1]), buffer.subdata(in: bodyStart..<(bodyStart + length)))
    }

    private func handle(_ request: (method: String, path: String, body: Data)) async {
        switch (request.method, request.path) {
        case ("FORBIDDEN", _):
            respond(403, jsonData(["error": "forbidden"]))
        case ("BAD", _):
            respond(400, jsonData(["error": "bad request"]))
        case ("GET", "/health"):
            respond(200, jsonData(["ready": await transcriber.ready]))
        case ("POST", "/transcribe"):
            // Copy rather than rebind: the body buffer is not guaranteed to be Float-aligned.
            var samples = [Float](repeating: 0, count: request.body.count / 4)
            _ = samples.withUnsafeMutableBytes { request.body.copyBytes(to: $0) }
            let started = Date()
            do {
                let result = try await transcriber.transcribe(samples)
                respond(200, jsonData([
                    "text": result.text,
                    "duration": result.duration,
                    "elapsedMs": Int(Date().timeIntervalSince(started) * 1000),
                ]))
            } catch {
                respond(500, jsonData(["error": "\(error)"]))
            }
        default:
            respond(404, jsonData(["error": "not found"]))
        }
    }

    private func respond(_ status: Int, _ body: Data) {
        let reason = status == 200 ? "OK" : "Error"
        var out = Data(
            "HTTP/1.1 \(status) \(reason)\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n".utf8)
        out.append(body)
        conn.send(content: out, completion: .contentProcessed { _ in self.conn.cancel() })
    }
}

func serve(port: UInt16, transcriber: Transcriber) throws {
    let params = NWParameters.tcp
    params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
    params.allowLocalEndpointReuse = true
    let listener = try NWListener(using: params)
    listener.newConnectionHandler = { Connection($0, transcriber).start() }
    listener.stateUpdateHandler = { state in
        if case .failed(let error) = state {
            FileHandle.standardError.write(Data("listener failed: \(error)\n".utf8))
            exit(1)
        }
    }
    listener.start(queue: .global())
}

// MARK: - Entry point

let args = CommandLine.arguments
let transcriber = Transcriber()
let loadStart = Date()
try await transcriber.load()
FileHandle.standardError.write(
    Data("model loaded in \(String(format: "%.1f", Date().timeIntervalSince(loadStart)))s\n".utf8))

if args.count >= 3, args[1] == "batch" {
    var results: [[String: Any]] = []
    for path in args.dropFirst(3) {
        let samples = try AudioConverter().resampleAudioFile(URL(fileURLWithPath: path))
        let started = Date()
        let result = try await transcriber.transcribe(samples)
        results.append([
            "file": path, "text": result.text, "duration": result.duration,
            "elapsedMs": Int(Date().timeIntervalSince(started) * 1000),
        ])
    }
    try jsonData(["results": results]).write(to: URL(fileURLWithPath: args[2]))
} else {
    let port = args.count >= 3 ? UInt16(args[2]) ?? 6017 : 6017
    try serve(port: port, transcriber: transcriber)
    FileHandle.standardError.write(Data("listening on 127.0.0.1:\(port)\n".utf8))
    while true { try await Task.sleep(nanoseconds: 3_600_000_000_000) }
}
