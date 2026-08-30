FROM public.ecr.aws/lambda/python:3.13

# numpy/scipy/scikit-learn/lifelines juntos estouram o limite de 250MB de uma
# Lambda Zip (mesmo motivo pelo qual a riskai-classificador virou container) —
# por isso essa Lambda ja nasce como Image.
RUN pip install --no-cache-dir \
        numpy \
        scipy \
        scikit-learn \
        joblib \
        lifelines \
        pandas \
        requests \
        boto3

# Os .pkl dos 3 niveis de Cox + metadata.json vem do Passo "6.1" do notebook
# (pasta modelos_treinados/). Copie essa pasta pra dentro deste diretorio
# antes de rodar `docker build`.
COPY modelos_treinados/ ${LAMBDA_TASK_ROOT}/modelos_treinados/
COPY handler.py ${LAMBDA_TASK_ROOT}/

CMD ["handler.lambda_handler"]
