package br.org.handmaxx.service.treino;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import br.org.handmaxx.app.error.custom.CustomException;
import br.org.handmaxx.app.error.global.ErrorResponse;
import br.org.handmaxx.dto.atleta.AtletaTreinoDTO;
import br.org.handmaxx.dto.enums.CategoriaDTO;
import br.org.handmaxx.dto.mensagem.MensagemDTO;
import br.org.handmaxx.dto.treino.TreinoCreateDTO;
import br.org.handmaxx.dto.treino.TreinoDTO;
import br.org.handmaxx.dto.treino.TreinoFullResponseDTO;
import br.org.handmaxx.dto.treino.TreinoResponseDTO;
import br.org.handmaxx.model.Atleta;
import br.org.handmaxx.model.Categoria;
import br.org.handmaxx.model.Treino;
import br.org.handmaxx.repository.AtletaRepository;
import br.org.handmaxx.repository.CategoriaRepository;
import br.org.handmaxx.repository.TreinoRepository;
import br.org.handmaxx.resource.WhatsappResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class TreinoServiceImpl implements TreinoService {

    @Inject
    TreinoRepository treinoRepository;

    @Inject
    AtletaRepository atletaRepository;

    @Inject
    CategoriaRepository categoriaRepository;

    @Inject
    WhatsappResource whatsAppResource;

    @Override
    @Transactional
    public TreinoFullResponseDTO create(TreinoCreateDTO treinoDTO) {
        Optional<Treino> treinoExistente = treinoRepository.find("dataHorario = ?1", treinoDTO.dataHorario()).firstResultOptional();

        if (treinoExistente.isPresent()) {
            ErrorResponse errorResponse = new ErrorResponse(
                    "Erro ao criar treino",
                    "TreinoServiceImpl(create): Já há treino cadastrado na mesma data e mesmo horário.",
                    403);
            throw new CustomException(errorResponse);
        }

        Treino treino = new Treino();

        treino.setLocal(treinoDTO.local());
        treino.setDataHorario(treinoDTO.dataHorario());

        if (treinoDTO.criarTreinoTodosAtletas()) {
            List<Atleta> todosAtletas = atletaRepository.findAll().list();
            treino.setListaAtletas(todosAtletas);
        } else if (!treinoDTO.listarAtletas().isEmpty()) {
            treino.setListaAtletas(relacionarAtletasPorIdsAtletas(treinoDTO.listarAtletas()));
        } else if (!treinoDTO.listarCategorias().isEmpty()) {
            treino.setListaAtletas(relacionarAtletasPorCategorias(treinoDTO.listarCategorias()));
        }else{
            throw new CustomException(new ErrorResponse("Nenhum atleta selecionado.", "TreinoServiceImpl(create)", 400));
        }

        try {
            treinoRepository.persist(treino);
        } catch (PersistenceException e) {
            ErrorResponse errorResponse = new ErrorResponse(
                    "Erro ao criar treino",
                    "TreinoServiceImpl(create): " + e.getMessage(),
                    500);
            throw new CustomException(errorResponse);
        }
        if(treinoDTO.notificarAtletasAgora())
            notificarTodosAtletasCreate(treino);
        
        return TreinoFullResponseDTO.valueOf(treino);
    }

    private List<Atleta> relacionarAtletasPorIdsAtletas(List<AtletaTreinoDTO> atletasListados) {
        List<Long> ids = atletasListados.stream().map(AtletaTreinoDTO::id).toList();
        List<Atleta> atletasEncontrados = atletaRepository.findByIds(ids);
        List<Long> idsNaoEncontrados = ids.stream()
                .filter(id -> atletasEncontrados.stream()
                .noneMatch(atleta -> atleta.getId().equals(id)))
                .toList();

        if (!idsNaoEncontrados.isEmpty()) {
            throw new CustomException(new ErrorResponse(
                    "Atletas não encontrados para os ID's: " + String.join(", ", idsNaoEncontrados.toString()),
                    "TreinoService(criarTreino)",
                    404
            ));
        }
        return atletasEncontrados;
    }

    private List<Atleta> relacionarAtletasPorCategorias(List<CategoriaDTO> categoriasListadas) {
        List<Integer> ids = categoriasListadas.stream().map(CategoriaDTO::id).toList();
        List<Categoria> categoriasEncontradas = categoriaRepository.findByIds(ids);
        List<Atleta> atletasEncontrados = atletaRepository.findByCategorias(categoriasEncontradas);
        return atletasEncontrados;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Treino treino = treinoRepository.findById(id);

        if (treino == null) {
            throw new CustomException(new ErrorResponse("Treino não encontrado.", "TreinoServiceImpl(delete)", 404));
        }
        try {
            notificarTodosAtletasDelete(treino);
            treinoRepository.delete(treino);
        } catch (Exception e) {
            throw new CustomException(new ErrorResponse("Erro no servidor: " + e.getCause().toString(), "TreinoServiceImpl(delete)", 500));
        }
    }

    @Override
    public TreinoFullResponseDTO findById(Long id) {
        Treino treino = treinoRepository.findById(id);
        if (treino == null) {
            throw new CustomException(new ErrorResponse("Erro ao procurar treino.", "TreinoServiceImpl(findById): Treino não encontrado", 404));
        }
        return TreinoFullResponseDTO.valueOf(treino);
    }

    @Override
    public List<TreinoResponseDTO> findAll() {
        List<Treino> treinos = treinoRepository.findAll().list();
        return treinos.stream().map(TreinoResponseDTO::valueOf).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TreinoFullResponseDTO update(TreinoDTO dto, Long id) {
        Treino treino = treinoRepository.findById(id);
        if (treino == null) {
            throw new CustomException(new ErrorResponse("Erro ao atualizar treino.", "TreinoServiceImpl(update): Treino não encontrado.", 404));
        }

        treino.setLocal(dto.local());
        treino.setDataHorario(dto.dataHorario());

        if (!dto.listarAtletas().isEmpty()) {
            List<Long> ids = dto.listarAtletas().stream().map(AtletaTreinoDTO::id).toList();
            List<Atleta> atletasEncontrados = atletaRepository.findByIds(ids);
            treino.setListaAtletas(atletasEncontrados);
        }

        notificarTodosAtletasUpdate(treino);
        return TreinoFullResponseDTO.valueOf(treino);
    }

    private void notificarTodosAtletasCreate(Treino treino) {
        for (Atleta atleta : treino.getListaAtletas()) {
            whatsAppResource.sendTextMessage(new MensagemDTO("55" + retirarPrimeiroNove(atleta.getTelefone()) + "@c.us", criarMensagemTreinoCadastro(atleta, treino), "default"));
            System.out.println("Enviado para " + retirarPrimeiroNove(atleta.getTelefone()) + "!");
        }
    }

    private void notificarTodosAtletasDelete(Treino treino) {
        for (Atleta atleta : treino.getListaAtletas()) {
            whatsAppResource.sendTextMessage(new MensagemDTO("55" + retirarPrimeiroNove(atleta.getTelefone()) + "@c.us", criarMensagemTreinoCancelado(atleta, treino), "default"));
            System.out.println("Enviado para " + retirarPrimeiroNove(atleta.getTelefone()) + "!");
        }
    }

    private void notificarTodosAtletasUpdate(Treino treino) {
        for (Atleta atleta : treino.getListaAtletas()) {
            whatsAppResource.sendTextMessage(new MensagemDTO("55" + retirarPrimeiroNove(atleta.getTelefone()) + "@c.us", criarMensagemTreinoAtualizando(atleta, treino), "default"));
            System.out.println("Enviado para " + retirarPrimeiroNove(atleta.getTelefone()) + "!");
        }
    }

    private String criarMensagemTreinoCadastro(Atleta atleta, Treino treino) {
        return "Olá, atleta " + atleta.getNome() + "!\n\nFoi agendado um treino na sua escolinha Handmaxx!\n\nSerá em "
                + formatarData(treino.getDataHorario().toLocalDate()) + ", às " + formatarHorario(treino.getDataHorario().toLocalTime()) + ".\nO local será no(a) "
                + treino.getLocal() + ".\n\n*Aguardamos você!*";
    }

    private String criarMensagemTreinoAtualizando(Atleta atleta, Treino treino) {
        return "Olá, atleta " + atleta.getNome() + "!\n\nSeu treino foi remarcado!\nAgora, será em "
        + formatarData(treino.getDataHorario().toLocalDate()) + ", às " + formatarHorario(treino.getDataHorario().toLocalTime()) + ".\nO local será no(a) "
        + treino.getLocal() + "\n\n*Aguardamos você!*";
    }

    private String criarMensagemTreinoCancelado(Atleta atleta, Treino treino) {
        return "Olá, atleta " + atleta.getNome() + "!\nEstamos cancelando seu treino que estava agendado para "
                + formatarData(treino.getDataHorario().toLocalDate()) + ", às " + formatarHorario(treino.getDataHorario().toLocalTime())
                + ".\n\n*Pedimos desculpas e contamos com a colaboração de todos.*";
    }

    private String formatarData(LocalDate date) {
        if (date == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return date.format(formatter);
    }

    private String formatarHorario(LocalTime time) {
        if (time == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        return time.format(formatter);
    }

    private String retirarPrimeiroNove(String numero) {

        if (numero == null || numero.length() <= 10) {
            return numero;
        }

        int indiceDoNove = numero.indexOf('9', 2);
        if (indiceDoNove == 2) {
            return numero.substring(0, 2) + numero.substring(3);
        }
        return numero;
    }

}
